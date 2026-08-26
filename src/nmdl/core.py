"""批量处理调度：扫描目录 -> 匹配 -> 下载歌词/封面 -> 缓存与报告。"""

from __future__ import annotations

import json
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

from . import lrc, matcher, netease, tagging
from .net import RateLimiter, set_proxy

AUDIO_EXT = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".wma", ".ape", ".aac"}
CACHE_NAME = ".nmdl_cache.json"
REPORT_NAME = "nmdl_report.txt"


@dataclass
class Options:
    directory: str = "."
    recursive: bool = False
    only: str = ""
    limit: int = 0
    force: bool = False
    retry_failed: bool = False
    dry_run: bool = False

    workers: int = 3
    rps: float = 1.0
    cooldown: float = 60.0
    search_limit: int = 15
    min_score: float = 45.0

    lyric: bool = True
    cover: bool = True
    translation: bool = True
    cover_size: int = 800
    encoding: str = "utf-8-sig"

    embed_cover: bool = False
    write_tags: bool = False

    proxy: str = ""
    rotate_ip: bool = False
    clash_api: str = "http://127.0.0.1:28845"
    clash_group: str = ""
    clash_secret: str = ""


@dataclass
class Stats:
    total: int = 0
    matched: int = 0
    unmatched: int = 0
    skipped: int = 0
    lyrics: int = 0
    covers: int = 0
    no_lyric: int = 0
    errors: int = 0
    seconds: float = 0.0
    records: dict = field(default_factory=dict)


def collect(opts: Options) -> list[str]:
    root = os.path.abspath(opts.directory)
    files: list[str] = []
    if opts.recursive:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if not d.startswith(".")]
            files += [os.path.join(dirpath, f) for f in filenames
                      if os.path.splitext(f)[1].lower() in AUDIO_EXT]
    else:
        files = [os.path.join(root, f) for f in os.listdir(root)
                 if os.path.splitext(f)[1].lower() in AUDIO_EXT
                 and os.path.isfile(os.path.join(root, f))]
    if opts.only:
        needle = opts.only.lower()
        files = [f for f in files if needle in os.path.basename(f).lower()]
    return sorted(files)


def _load_cache(root: str) -> dict:
    path = os.path.join(root, CACHE_NAME)
    if os.path.exists(path):
        try:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def _needs_work(rec: dict) -> bool:
    """这条缓存记录是不是还有没搞定的东西（用于 --retry-failed）。"""
    if rec.get("status") != "ok":
        return True
    return rec.get("lyric") not in (None, "ok", "纯音乐/无歌词") \
        or rec.get("cover") not in (None, "ok")


def _process(path: str, opts: Options, limiter, cache: dict, lock, stats: Stats,
             index: int, total: int, log) -> None:
    name = os.path.basename(path)
    stem = os.path.splitext(path)[0]
    lrc_path, jpg_path = stem + ".lrc", stem + ".jpg"

    want_lrc = opts.lyric and (opts.force or not os.path.exists(lrc_path))
    want_cover = opts.cover and (opts.force or not os.path.exists(jpg_path))
    if not want_lrc and not want_cover:
        with lock:
            stats.skipped += 1
        return

    file_stem = os.path.splitext(name)[0]
    artists, title = matcher.split_name(file_stem)
    duration = tagging.duration_of(path)
    rec: dict = {"file": name, "title": title, "artists": artists,
                 "duration": round(duration, 1), "ts": int(time.time())}

    # ---- 搜索并选出最佳匹配 ----
    best, best_score, best_detail = None, 0.0, ""
    error = ""
    got_response = False
    for keyword in matcher.keywords_for(title, artists):
        try:
            songs = netease.search(keyword, opts.search_limit, limiter)
            got_response = True
        except Exception as e:
            error = str(e)
            continue
        for i, song in enumerate(songs):
            sc, detail = matcher.score(song, title, artists, file_stem, duration, i)
            if best is None or sc > best_score:
                best, best_score, best_detail = song, sc, detail
        if best is not None and best_score >= 100:
            break                               # 已经很有把握，不再放宽关键词

    if best is None or best_score < opts.min_score:
        rec["status"] = "no_match"
        rec["score"] = round(best_score, 1) if best else None
        if best is not None:
            rec["candidate"] = str(best)
        if not got_response and error:
            rec["error"] = error
            rec["status"] = "error"
        with lock:
            if rec["status"] == "error":
                stats.errors += 1
            else:
                stats.unmatched += 1
            cache[name] = rec
        log("[%d/%d] ?  %-50s  未匹配%s %s" % (
            index, total, name[:50],
            ("(最高分 %.0f)" % best_score) if best else "", rec.get("error", "")))
        return

    rec.update({"id": best.id, "score": round(best_score, 1),
                "detail": best_detail, "matched": str(best), "album": best.album})
    notes = []
    cover_bytes = None

    # ---- 歌词 ----
    if want_lrc:
        try:
            raw, translation, note = netease.get_lyric(best.id, limiter)
            text = lrc.merge(raw, translation, opts.translation) if raw else ""
            if text:
                if not opts.dry_run:
                    with open(lrc_path, "w", encoding=opts.encoding, newline="\n") as f:
                        f.write(text)
                rec["lyric"] = "ok"
                notes.append("歌词OK" + ("(含翻译)" if translation.strip()
                                         and opts.translation else ""))
            else:
                rec["lyric"] = note or "歌词无时间轴"
                notes.append("歌词-" + rec["lyric"])
        except Exception as e:
            rec["lyric"] = "error: %s" % e
            notes.append("歌词-异常")

    # ---- 封面 ----
    if want_cover:
        try:
            pic_url = best.pic_url or netease.get_pic_url(best.id, limiter)
            if pic_url:
                cover_bytes = netease.download_cover(pic_url, opts.cover_size)
                if not opts.dry_run:
                    with open(jpg_path, "wb") as f:
                        f.write(cover_bytes)
                rec["cover"] = "ok"
                notes.append("封面OK")
            else:
                rec["cover"] = "无封面地址"
                notes.append("封面-无地址")
        except Exception as e:
            rec["cover"] = "error: %s" % e
            notes.append("封面-异常")

    # ---- 可选：写进音频文件 ----
    if (opts.embed_cover or opts.write_tags) and not opts.dry_run:
        try:
            if tagging.write_metadata(path, cover_bytes,
                                      {"title": best.title, "artist": best.artist,
                                       "album": best.album},
                                      opts.embed_cover, opts.write_tags):
                notes.append("已写入标签")
        except Exception as e:
            rec["embed"] = "error: %s" % e
            notes.append("写标签失败")

    rec["status"] = "ok"
    with lock:
        stats.matched += 1
        if rec.get("lyric") == "ok":
            stats.lyrics += 1
        elif rec.get("lyric") == "纯音乐/无歌词":
            stats.no_lyric += 1
        if rec.get("cover") == "ok":
            stats.covers += 1
        cache[name] = rec
    log("[%d/%d] %s %-50s -> %-40s [%s|%.0f] %s" % (
        index, total, "OK" if best_score >= 95 else "~ ", name[:50],
        str(best)[:40], best_detail, best_score, " ".join(notes)))


def write_report(root: str, cache: dict) -> str:
    bad = [v for v in cache.values() if _needs_work(v)]
    path = os.path.join(root, REPORT_NAME)
    with open(path, "w", encoding="utf-8") as f:
        f.write("需要人工确认的条目：%d 条\n" % len(bad))
        f.write("（可以手动改文件名成「歌手 - 歌名」后重跑，或用 --only 单独处理）\n")
        f.write("=" * 78 + "\n")
        for v in sorted(bad, key=lambda x: x.get("file", "")):
            f.write("%s\n" % v.get("file"))
            f.write("    匹配: %s (score=%s)\n" % (
                v.get("matched") or v.get("candidate") or "-", v.get("score")))
            f.write("    歌词: %s   封面: %s\n" % (v.get("lyric", "-"), v.get("cover", "-")))
            if v.get("error"):
                f.write("    错误: %s\n" % v["error"])
            f.write("\n")
    return path


def run(opts: Options, log=print) -> Stats:
    root = os.path.abspath(opts.directory)
    cache = _load_cache(root)
    files = collect(opts)
    if opts.retry_failed:
        files = [f for f in files if _needs_work(cache.get(os.path.basename(f), {}))]
    if opts.limit:
        files = files[:opts.limit]

    if opts.proxy:
        set_proxy(opts.proxy)

    rotator = None
    if opts.rotate_ip:
        from .clash import ClashRotator
        rotator = ClashRotator(opts.clash_api, opts.clash_group, opts.clash_secret,
                               opts.proxy or "http://127.0.0.1:7890", log=log)
        try:
            rotator.prepare()
        except Exception as e:
            log("[clash] 初始化失败，改为等待冷却：%s" % e)
            rotator = None

    def on_throttle() -> bool:
        if rotator is None:
            return False
        try:
            rotator.rotate()
            return True
        except Exception as e:
            log("[clash] 换节点失败：%s" % e)
            return False

    def on_wait(secs: float, reason: str) -> None:
        if secs > 3:
            log("  ...被限流，等待 %.0f 秒后继续" % secs)

    limiter = RateLimiter(rps=opts.rps, cooldown=opts.cooldown,
                          on_wait=on_wait, on_throttle=on_throttle if rotator else None)

    stats = Stats(total=len(files))
    lock = threading.Lock()
    log("目录: %s" % root)
    log("待处理: %d 首   并发: %d   限速: %.2f 请求/秒%s%s" % (
        len(files), opts.workers, opts.rps,
        "   [DRY-RUN]" if opts.dry_run else "",
        "   [换IP已开启]" if rotator else ""))
    log("-" * 100)

    t0 = time.time()
    try:
        with ThreadPoolExecutor(max_workers=max(1, opts.workers)) as pool:
            futures = [pool.submit(_process, f, opts, limiter, cache, lock, stats,
                                   i + 1, len(files), log)
                       for i, f in enumerate(files)]
            for fut in futures:
                fut.result()
    except KeyboardInterrupt:
        log("\n已中断，正在保存进度…")
    finally:
        if rotator:
            rotator.restore()
    stats.seconds = time.time() - t0
    stats.records = cache

    if not opts.dry_run:
        with open(os.path.join(root, CACHE_NAME), "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False, indent=1)
        report = write_report(root, cache)
    else:
        report = ""

    log("-" * 100)
    log("完成：匹配 %d / 未匹配 %d / 跳过 %d / 出错 %d" % (
        stats.matched, stats.unmatched, stats.skipped, stats.errors))
    log("      歌词 %d 个（另有 %d 首是纯音乐），封面 %d 个，用时 %.0f 秒" % (
        stats.lyrics, stats.no_lyric, stats.covers, stats.seconds))
    if report:
        log("明细报告：%s" % report)
    return stats
