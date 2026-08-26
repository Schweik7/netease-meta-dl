"""网易云接口封装。

接口地址与 MusicPlayer2 的 ``NeteaseLyricDownload.cpp`` 一致：

* 搜索   ``/api/search/get/?s=&limit=&type=1&offset=0``
* 歌词   ``/api/song/lyric?os=osx&id=&lv=-1&kv=-1&tv=-1``
* 封面   ``/api/song/detail/?id=&ids=[]`` 里的 ``picUrl``

额外用了 ``/api/cloudsearch/pc``：返回结构里直接带 ``al.picUrl``，命中时可以省掉
一次 detail 请求（请求数少三分之一，也就更不容易撞限流）。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

from .net import api_json, get_bytes

CLOUDSEARCH_URL = "https://music.163.com/api/cloudsearch/pc"
SEARCH_URL = "https://music.163.com/api/search/get/"
LYRIC_URL = "https://music.163.com/api/song/lyric"
DETAIL_URL = "https://music.163.com/api/song/detail/"


@dataclass
class Song:
    id: int
    title: str = ""
    artists: list[str] = field(default_factory=list)
    album: str = ""
    duration: float = 0.0           # 秒
    pic_url: str = ""

    @property
    def artist(self) -> str:
        return "/".join(self.artists)

    def __str__(self) -> str:
        return "%s - %s" % (self.artist, self.title)


def _song_from_cloudsearch(d: dict) -> Song:
    al = d.get("al") or {}
    return Song(
        id=d.get("id", 0),
        title=d.get("name", "") or "",
        artists=[a.get("name", "") for a in (d.get("ar") or []) if a.get("name")],
        album=al.get("name", "") or "",
        duration=(d.get("dt") or 0) / 1000.0,
        pic_url=al.get("picUrl") or "",
    )


def _song_from_search(d: dict) -> Song:
    al = d.get("album") or {}
    return Song(
        id=d.get("id", 0),
        title=d.get("name", "") or "",
        artists=[a.get("name", "") for a in (d.get("artists") or []) if a.get("name")],
        album=al.get("name", "") or "",
        duration=(d.get("duration") or 0) / 1000.0,
        pic_url=al.get("picUrl") or "",
    )


def search(keyword: str, limit: int = 15, limiter=None) -> list[Song]:
    """搜索歌曲。优先用 cloudsearch（带封面地址），失败时退回旧接口。"""
    try:
        j = api_json(CLOUDSEARCH_URL,
                     params={"s": keyword, "type": 1, "offset": 0,
                             "total": "true", "limit": limit},
                     limiter=limiter)
        songs = (j.get("result") or {}).get("songs")
        if songs:
            return [_song_from_cloudsearch(x) for x in songs]
    except Exception:
        pass                                    # 交给下面的旧接口
    j = api_json(SEARCH_URL,
                 params={"s": keyword, "type": 1, "offset": 0,
                         "total": "true", "limit": limit},
                 limiter=limiter)
    return [_song_from_search(x) for x in ((j.get("result") or {}).get("songs") or [])]


def get_lyric(song_id: int, limiter=None) -> tuple[str, str, str]:
    """返回 (原文lrc, 翻译lrc, 说明)。取不到时原文为空字符串。"""
    j = api_json(LYRIC_URL,
                 params={"os": "osx", "id": song_id, "lv": -1, "kv": -1, "tv": -1},
                 limiter=limiter)
    if j.get("nolyric"):
        return "", "", "纯音乐/无歌词"
    if j.get("uncollected"):
        return "", "", "暂无歌词收录"
    raw = (j.get("lrc") or {}).get("lyric") or ""
    tra = (j.get("tlyric") or {}).get("lyric") or ""
    if not raw.strip():
        return "", "", "歌词为空"
    return raw, tra, ""


def get_pic_url(song_id: int, limiter=None) -> str:
    """搜索结果里没带封面地址时，用歌曲详情接口补一次。"""
    j = api_json(DETAIL_URL,
                 params={"id": song_id, "ids": json.dumps([song_id]), "csrf_token": ""},
                 limiter=limiter)
    songs = j.get("songs") or []
    if not songs:
        return ""
    album = songs[0].get("album") or {}
    return album.get("picUrl") or album.get("blurPicUrl") or ""


def download_cover(pic_url: str, size: int = 800) -> bytes:
    """按指定边长下载封面。图片走 CDN，不占接口限流额度。"""
    url = pic_url
    if url.startswith("http://"):
        url = "https://" + url[7:]
    if size:
        url = "%s?param=%dy%d" % (url, size, size)
    data = get_bytes(url)
    if len(data) < 2048:
        raise RuntimeError("封面数据过小(%d 字节)" % len(data))
    return data
