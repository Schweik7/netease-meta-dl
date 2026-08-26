"""从文件名解析曲目信息，并对搜索结果打分选出最佳匹配。

打分权重照搬 MusicPlayer2 ``CLyricDownloadCommon::SelectMatchedItem``：
标题 0.4 / 艺术家 0.4 / 文件名↔标题 0.3 / 列表排序 0.05（这里统一放大 100 倍），
另外补上它注释里列了权值、但代码中未实现的「时长」判定——本地文件的时长是最硬的
证据，能有效排掉同名的现场版、翻唱和重制版。
"""

from __future__ import annotations

import difflib
import re
import unicodedata

BRACKETS = re.compile(r"[（(\[【].*?[)）\]】]")
NOISE = re.compile(
    r"\b(official|audio|video|mv|hd|hq|remaster(ed)?|radio\s*edit|live|"
    r"inst(rumental)?|cover|feat\.?|ft\.?)\b", re.I)
PUNCT = re.compile(r"[\s\-_·,，.。!！?？'\"“”‘’:：;；/\\|~～&+*^%$#@()（）\[\]【】{}<>《》]+")
ARTIST_SEP = re.compile(r"[,，/&;、]|\bfeat\.?\b|\bft\.?\b", re.I)


def norm(s: str) -> str:
    """比较用的强归一化：全角转半角、去括号内容、去噪声词、去标点、转小写。"""
    s = unicodedata.normalize("NFKC", s or "").lower()
    s = BRACKETS.sub(" ", s)
    s = NOISE.sub(" ", s)
    return PUNCT.sub("", s)


def sim(a: str, b: str) -> float:
    """归一化后的字符串相似度，作用等价于 MusicPlayer2 的 StringSimilarDegree_LD。"""
    a, b = norm(a), norm(b)
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    return difflib.SequenceMatcher(None, a, b).ratio()


def clean_title(t: str) -> str:
    """搜索用的宽松标题：去掉括号里的补充说明（remix / live / 电影版之类）。"""
    c = BRACKETS.sub(" ", unicodedata.normalize("NFKC", t or "")).strip()
    return c if len(c) >= 2 else (t or "").strip()


def split_name(stem: str) -> tuple[list[str], str]:
    """``'歌手1,歌手2 - 歌名 - 副标题'`` -> ``(['歌手1','歌手2'], '歌名 - 副标题')``

    按第一个分隔符切分，副标题保留在标题里（网易云的曲名本身常带 ``-``）。
    """
    for sep in (" - ", " – ", " — ", " _ ", "-"):
        if sep in stem:
            left, right = (x.strip() for x in stem.split(sep, 1))
            if left and right:
                artists = [a.strip() for a in ARTIST_SEP.split(left) if a.strip()]
                return artists or [left], right
    return [], stem.strip()


def score(song, title: str, artists: list[str], stem: str,
          duration: float, index: int = 0) -> tuple[float, str]:
    """给一个搜索结果打分，返回 (分数, 命中项说明)。满分约 138。"""
    detail: list[str] = []
    s = 0.0

    ts = sim(title, song.title)
    s += ts * 40
    if ts > 0.95:
        detail.append("title=")
    elif ts > 0.6:
        detail.append("title~")

    as_ = sim("/".join(artists), song.artist)
    if artists and song.artists and as_ < 0.6:
        # 多歌手时顺序/数量常对不上，退而求其次：只要有一个歌手对上就算
        as_ = max(sim(a, sa) for a in artists for sa in song.artists)
    s += as_ * 30
    if as_ > 0.9:
        detail.append("artist=")
    elif as_ > 0.6:
        detail.append("artist~")

    s += sim(stem, "%s %s" % (song.artist, song.title)) * 20
    s += (1 - index * 0.02) * 3         # 网易云返回结果越靠前，关联度一般越高

    if duration and song.duration:
        diff = abs(song.duration - duration)
        if diff <= 2:
            s += 45
            detail.append("dur<2s")
        elif diff <= 5:
            s += 32
            detail.append("dur<5s")
        elif diff <= 10:
            s += 14
            detail.append("dur<10s")
        elif diff > 25:
            s -= 35
            detail.append("dur!!")
        else:
            s -= 8
    return s, ",".join(detail)


def keywords_for(title: str, artists: list[str]) -> list[str]:
    """搜索关键词，从最精确逐步放宽。"""
    main = artists[0] if artists else ""
    ct = clean_title(title)
    out: list[str] = []
    for k in ("%s %s" % (main, title), "%s %s" % (main, ct), title, ct):
        k = " ".join(k.split())
        if k and k not in out:
            out.append(k)
    return out
