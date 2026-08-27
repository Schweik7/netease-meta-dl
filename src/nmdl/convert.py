"""FLAC -> MP3 转换：调用 ffmpeg，保留原有标签和内嵌封面。

这是一个独立功能，跟下载歌词/封面互不相干——命令行是 ``nmdl convert``，
GUI 里是左侧「格式转换」那一页。想给转换出来的 mp3 配歌词封面，转完再跑一次
``nmdl download`` 就行。
"""

from __future__ import annotations

import os
import shutil
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

SOURCE_EXT = {".flac"}                          # 目前只转 flac
TARGET_EXT = ".mp3"

# Windows 下别让每个 ffmpeg 子进程弹一个黑框（GUI 模式尤其难看）
_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


@dataclass
class Options:
    directory: str = "."
    recursive: bool = False
    only: str = ""
    limit: int = 0
    force: bool = False
    dry_run: bool = False

    bitrate: str = "320k"
    output: str = ""
    workers: int = 0
    delete_source: bool = False
    ffmpeg: str = ""


@dataclass
class Stats:
    total: int = 0
    converted: int = 0
    skipped: int = 0
    errors: int = 0
    seconds: float = 0.0


def find_ffmpeg(explicit: str = "") -> str:
    """定位 ffmpeg 可执行文件；找不到抛 FileNotFoundError。"""
    cand = explicit.strip() or "ffmpeg"
    path = shutil.which(cand)
    if not path and os.path.isfile(cand):
        path = cand
    if not path:
        raise FileNotFoundError(
            "找不到 ffmpeg，转换功能依赖它。\n"
            "  Windows: winget install Gyan.FFmpeg （装完重开终端）\n"
            "  或者用 --ffmpeg 直接指定 ffmpeg.exe 的路径")
    return path


def _bitrate_args(bitrate: str) -> list[str]:
    """'320k' -> 固定码率；'V0' -> LAME 的 VBR 质量档（V0 体积小、听感接近 320k）。"""
    b = bitrate.strip()
    if len(b) > 1 and b[0] in "vV" and b[1:].isdigit():
        return ["-q:a", b[1:]]
    return ["-b:a", b if b.lower().endswith("k") else b + "k"]


def target_path(src: str, root: str, out_dir: str) -> str:
    """源文件对应的 mp3 路径。out_dir 为空则原地转换，否则按相对路径复刻目录结构。"""
    dst = os.path.splitext(src)[0] + TARGET_EXT
    if not out_dir:
        return dst
    return os.path.join(os.path.abspath(out_dir), os.path.relpath(dst, root))


def _run_ffmpeg(ffmpeg: str, src: str, dst: str, bitrate: str, with_cover: bool) -> None:
    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", src]
    if with_cover:
        # 0:v? 里的问号表示「没有内嵌图片也别报错」，图片直接 copy 进 ID3 的 APIC
        cmd += ["-map", "0:a:0", "-map", "0:v?", "-c:v", "copy",
                "-disposition:v", "attached_pic"]
    else:
        cmd += ["-map", "0:a:0", "-vn"]
    # 显式 -f mp3：输出先写成 .part 临时文件，扩展名推不出容器格式
    cmd += ["-c:a", "libmp3lame", *_bitrate_args(bitrate),
            "-map_metadata", "0", "-id3v2_version", "3", "-f", "mp3", dst]
    p = subprocess.run(cmd, stdin=subprocess.DEVNULL,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       text=True, encoding="utf-8", errors="replace",
                       creationflags=_NO_WINDOW)
    if p.returncode != 0:
        tail = (p.stderr or "").strip().splitlines()
        raise RuntimeError(tail[-1] if tail else "ffmpeg 退出码 %d" % p.returncode)


def convert_one(ffmpeg: str, src: str, dst: str, bitrate: str = "320k") -> None:
    """转一个文件。先写临时文件再改名，中断不会留下半成品 mp3。"""
    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    tmp = dst + ".part"
    try:
        try:
            _run_ffmpeg(ffmpeg, src, tmp, bitrate, with_cover=True)
        except RuntimeError:
            # 有些 FLAC 的内嵌图片格式没法直接塞进 ID3，丢掉图片重来一次；
            # 封面反正可以事后用 nmdl download 补。
            _run_ffmpeg(ffmpeg, src, tmp, bitrate, with_cover=False)
        os.replace(tmp, dst)
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass


def run(opts: Options, log=print) -> Stats:
    """扫描目录里的 FLAC 并批量转成 MP3。"""
    from .core import collect                   # 只借它的目录扫描，避免重复实现

    root = os.path.abspath(opts.directory)
    files = collect(opts, SOURCE_EXT)
    if opts.limit:
        files = files[:opts.limit]
    stats = Stats(total=len(files))

    log("目录: %s" % root)
    if not files:
        log("没找到 .flac 文件，无事可做。")
        return stats

    try:
        ffmpeg = find_ffmpeg(opts.ffmpeg)
    except FileNotFoundError as e:
        log(str(e))
        stats.errors += 1
        return stats

    workers = opts.workers or min(8, os.cpu_count() or 2)
    total = len(files)
    lock = threading.Lock()
    counter = [0]

    log("待转换: %d 个 FLAC   码率: %s   并发: %d%s%s%s" % (
        total, opts.bitrate, workers,
        "   输出到 %s" % os.path.abspath(opts.output) if opts.output else "",
        "   [DRY-RUN]" if opts.dry_run else "",
        "   [转换后删除源文件]" if opts.delete_source else ""))
    log("-" * 100)

    def one(src: str) -> None:
        dst = target_path(src, root, opts.output)
        name = os.path.basename(src)
        with lock:
            counter[0] += 1
            i = counter[0]

        if os.path.exists(dst) and not opts.force:
            with lock:
                stats.skipped += 1
            log("[%d/%d] -  %-50s  已有 mp3，跳过" % (i, total, name[:50]))
            return
        if opts.dry_run:
            log("[%d/%d] .. %-50s -> %s" % (i, total, name[:50], os.path.basename(dst)))
            return
        try:
            convert_one(ffmpeg, src, dst, opts.bitrate)
        except Exception as e:
            with lock:
                stats.errors += 1
            log("[%d/%d] !  %-50s  转换失败：%s" % (i, total, name[:50], e))
            return
        if opts.delete_source:
            try:
                os.remove(src)
            except OSError as e:
                log("       源文件删不掉：%s（%s）" % (name, e))
        with lock:
            stats.converted += 1
        log("[%d/%d] OK %-50s -> %s" % (i, total, name[:50], os.path.basename(dst)))

    t0 = time.time()
    try:
        with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
            for fut in [pool.submit(one, f) for f in files]:
                fut.result()
    except KeyboardInterrupt:
        log("\n已中断。")
    stats.seconds = time.time() - t0

    log("-" * 100)
    log("完成：转换 %d / 跳过 %d / 失败 %d，用时 %.0f 秒" % (
        stats.converted, stats.skipped, stats.errors, stats.seconds))
    return stats
