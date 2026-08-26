"""LRC 解析与合成。

网易云的原文和翻译是两份独立的 LRC。合并时把翻译写成**同一时间戳的下一行**——
这正是 MusicPlayer2 识别翻译的方式（相同时间戳的第二行视为上一行的译文），
其它常见播放器也大多兼容。
"""

from __future__ import annotations

import bisect

import re

from .matcher import norm

TIME_RE = re.compile(r"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]")


def parse(text: str) -> tuple[list[str], list[tuple[int, str]]]:
    """-> (元信息行, [(毫秒, 文本), ...])，按时间排序。"""
    meta: list[str] = []
    timed: list[tuple[int, str]] = []
    for raw in (text or "").splitlines():
        raw = raw.strip()
        if not raw:
            continue
        tags = list(TIME_RE.finditer(raw))
        if not tags:
            if raw.startswith("[") and raw.endswith("]"):
                meta.append(raw)
            continue
        content = raw[tags[-1].end():].strip()
        for m in tags:              # 一行可能挂多个时间戳（副歌复用）
            ms = int(m.group(1)) * 60000 + int(m.group(2)) * 1000
            if m.group(3):
                ms += int(m.group(3).ljust(3, "0")[:3])
            timed.append((ms, content))
    timed.sort(key=lambda x: x[0])
    return meta, timed


def fmt_ts(ms: int) -> str:
    return "[%02d:%02d.%03d]" % (ms // 60000, (ms // 1000) % 60, ms % 1000)


def merge(raw_lyric: str, translation: str = "", with_translation: bool = True) -> str:
    """合成最终 LRC 文本；没有时间轴则返回空字符串。"""
    meta, timed = parse(raw_lyric)
    if not timed:
        return ""

    tmap: dict[int, str] = {}
    if with_translation and translation:
        for ms, content in parse(translation)[1]:
            if content:
                tmap[ms] = content
    keys = sorted(tmap)

    out = list(meta)
    for ms, content in timed:
        out.append(fmt_ts(ms) + content)
        tr = tmap.get(ms)
        if tr is None and keys:                 # 两份 LRC 的时间戳偶有毫秒级偏差
            i = bisect.bisect_left(keys, ms)
            for j in (i - 1, i):
                if 0 <= j < len(keys) and abs(keys[j] - ms) <= 60:
                    tr = tmap[keys[j]]
                    break
        if tr and norm(tr) != norm(content):
            out.append(fmt_ts(ms) + tr)
    return "\n".join(out) + "\n"
