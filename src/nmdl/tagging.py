"""读取音频时长，以及（可选地）把封面/标签写进音频文件。

默认流程只写同名的 .lrc / .jpg，不碰音频文件本身；只有显式开启
``--embed-cover`` / ``--write-tags`` 才会改动音频。
"""

from __future__ import annotations

import os

import mutagen
from mutagen.flac import FLAC, Picture
from mutagen.id3 import APIC, ID3, TALB, TIT2, TPE1


def duration_of(path: str) -> float:
    """音频时长（秒）；读不出来返回 0。"""
    try:
        au = mutagen.File(path)
        return float(au.info.length) if au is not None and au.info else 0.0
    except Exception:
        return 0.0


def write_metadata(path: str, cover: bytes | None, tags: dict,
                   embed_cover: bool, write_tags: bool) -> bool:
    """把封面和/或标签写入音频文件。返回是否写了。"""
    if not (embed_cover and cover) and not write_tags:
        return False
    ext = os.path.splitext(path)[1].lower()

    if ext == ".flac":
        f = FLAC(path)
        if embed_cover and cover:
            pic = Picture()
            pic.type = 3                        # front cover
            pic.mime = "image/jpeg"
            pic.desc = "Cover"
            pic.data = cover
            f.clear_pictures()
            f.add_picture(pic)
        if write_tags:
            for key in ("title", "artist", "album"):
                if tags.get(key):
                    f[key] = tags[key]
        f.save()
        return True

    if ext == ".mp3":
        try:
            id3 = ID3(path)
        except Exception:
            id3 = ID3()                         # 文件还没有 ID3 段
        if embed_cover and cover:
            id3.delall("APIC")
            id3.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Cover", data=cover))
        if write_tags:
            if tags.get("title"):
                id3.setall("TIT2", [TIT2(encoding=3, text=tags["title"])])
            if tags.get("artist"):
                id3.setall("TPE1", [TPE1(encoding=3, text=tags["artist"])])
            if tags.get("album"):
                id3.setall("TALB", [TALB(encoding=3, text=tags["album"])])
        id3.save(path, v2_version=3)            # v2.3 兼容性最好
        return True

    return False                                # 其它格式暂不写入
