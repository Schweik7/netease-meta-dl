package com.schweik.nmdl.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 批量处理调度：扫描目录 -> 匹配 -> 下载歌词/封面 -> 缓存与报告。
 *
 * 对应桌面版的 `core.py`，缓存文件 `.nmdl_cache.json` 的字段也保持一致——
 * 同一个音乐文件夹在电脑和手机上轮流跑，进度是互通的。
 *
 * 桌面版里的代理、Clash 换 IP、写入音频标签这几项手机上暂不提供。
 */
object Downloader {

    const val CACHE_NAME = ".nmdl_cache.json"
    const val REPORT_NAME = "nmdl_report.txt"

    data class Options(
        val directory: String = "",
        val recursive: Boolean = true,
        val only: String = "",
        val limit: Int = 0,
        val force: Boolean = false,
        val retryFailed: Boolean = false,
        val dryRun: Boolean = false,

        val workers: Int = 3,
        val rps: Double = 1.0,
        val cooldown: Double = 60.0,
        val searchLimit: Int = 15,
        val minScore: Double = 45.0,

        val lyric: Boolean = true,
        val cover: Boolean = true,
        val translation: Boolean = true,
        val coverSize: Int = 800,
        /** 歌词写 UTF-8 BOM，对应桌面版默认的 utf-8-sig，播放器兼容性最好。 */
        val lyricBom: Boolean = true,
    )

    data class Stats(
        var total: Int = 0,
        var matched: Int = 0,
        var unmatched: Int = 0,
        var skipped: Int = 0,
        var lyrics: Int = 0,
        var covers: Int = 0,
        var noLyric: Int = 0,
        var errors: Int = 0,
        var seconds: Double = 0.0,
    )

    /** 界面回调：日志行、进度（当前/总数）。 */
    class Sink(
        val log: (String) -> Unit = {},
        val progress: (Int, Int) -> Unit = { _, _ -> },
    )

    // ---- 缓存读写 ----

    private fun loadCache(root: File): JSONObject {
        val f = File(root, CACHE_NAME)
        if (!f.exists()) return JSONObject()
        return try {
            JSONObject(f.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /** 这条缓存记录是不是还有没搞定的东西（用于「只重试失败项」）。 */
    private fun needsWork(rec: JSONObject?): Boolean {
        if (rec == null) return true
        if (rec.optString("status") != "ok") return true
        val lyric = rec.optString("lyric", "")
        val cover = rec.optString("cover", "")
        val lyricBad = lyric.isNotEmpty() && lyric != "ok" && lyric != "纯音乐/无歌词"
        val coverBad = cover.isNotEmpty() && cover != "ok"
        return lyricBad || coverBad
    }

    // ---- 主流程 ----

    suspend fun run(context: Context, opts: Options, sink: Sink): Stats = coroutineScope {
        val root = File(opts.directory)
        val stats = Stats()

        if (!root.isDirectory) {
            sink.log("目录不存在：${root.absolutePath}")
            stats.errors++
            return@coroutineScope stats
        }

        val cache = loadCache(root)
        var files = Scanner.collect(root, opts.recursive, opts.only)
        if (opts.retryFailed) {
            files = files.filter { needsWork(cache.optJSONObject(it.name)) }
        }
        if (opts.limit > 0) files = files.take(opts.limit)
        stats.total = files.size

        sink.log("目录: ${root.absolutePath}")
        if (files.isEmpty()) {
            sink.log("没找到要处理的音乐文件。")
            return@coroutineScope stats
        }
        sink.log(
            "待处理: %d 首   并发: %d   限速: %.2f 请求/秒%s".format(
                files.size, opts.workers, opts.rps, if (opts.dryRun) "   [试运行]" else ""
            )
        )
        sink.log("-".repeat(60))

        // 时长表先建好，几百首能省下几十秒
        val durations = withContext(Dispatchers.IO) { Scanner.durationMap(context) }

        val limiter = Http.RateLimiter(
            rps = opts.rps,
            baseCooldown = opts.cooldown,
            onWait = { secs ->
                if (secs > 3) sink.log("  …被限流，等待 ${Http.humanSeconds(secs)}后继续")
            },
        )
        val lock = Mutex()
        val gate = Semaphore(opts.workers.coerceAtLeast(1))
        var done = 0

        val t0 = System.currentTimeMillis()
        try {
            files.mapIndexed { i, f ->
                async {
                    gate.withPermit {
                        process(
                            f, opts, limiter, cache, lock, stats, durations,
                            i + 1, files.size, sink
                        )
                        lock.withLock {
                            done += 1
                            sink.progress(done, files.size)
                        }
                    }
                }
            }.awaitAll()
        } finally {
            stats.seconds = (System.currentTimeMillis() - t0) / 1000.0
            // 中途按停止也要把进度写下来，否则跑过的几百首下次还得重来。
            // NonCancellable：协程已经处于取消状态，不套这层写不进去。
            if (!opts.dryRun) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        File(root, CACHE_NAME).writeText(cache.toString(1), Charsets.UTF_8)
                        writeReport(root, cache)
                    } catch (e: Exception) {
                        sink.log("缓存/报告写入失败：${e.message}")
                    }
                }
            }
        }

        sink.log("-".repeat(60))
        sink.log(
            "完成：匹配 %d / 未匹配 %d / 跳过 %d / 出错 %d".format(
                stats.matched, stats.unmatched, stats.skipped, stats.errors
            )
        )
        sink.log(
            "      歌词 %d 个（另有 %d 首是纯音乐），封面 %d 个，用时 %.0f 秒".format(
                stats.lyrics, stats.noLyric, stats.covers, stats.seconds
            )
        )
        stats
    }

    private suspend fun process(
        file: File,
        opts: Options,
        limiter: Http.RateLimiter,
        cache: JSONObject,
        lock: Mutex,
        stats: Stats,
        durations: Map<String, Double>,
        index: Int,
        total: Int,
        sink: Sink,
    ) {
        val name = file.name
        val stem = file.absolutePath.substringBeforeLast('.')
        val lrcFile = File("$stem.lrc")
        val jpgFile = File("$stem.jpg")

        val wantLrc = opts.lyric && (opts.force || !lrcFile.exists())
        val wantCover = opts.cover && (opts.force || !jpgFile.exists())
        if (!wantLrc && !wantCover) {
            lock.withLock { stats.skipped++ }
            return
        }

        val fileStem = file.nameWithoutExtension
        val (artists, title) = Matcher.splitName(fileStem)
        val duration = withContext(Dispatchers.IO) { Scanner.durationOf(file, durations) }

        val rec = JSONObject()
        rec.put("file", name)
        rec.put("title", title)
        rec.put("artists", JSONArray(artists))
        rec.put("duration", Math.round(duration * 10) / 10.0)
        rec.put("ts", System.currentTimeMillis() / 1000)

        // ---- 搜索并选出最佳匹配 ----
        var best: Song? = null
        var bestScore = 0.0
        var bestDetail = ""
        var error = ""
        var gotResponse = false

        for (keyword in Matcher.keywordsFor(title, artists)) {
            val songs = try {
                val r = Netease.search(keyword, opts.searchLimit, limiter)
                gotResponse = true
                r
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                continue
            }
            for ((i, song) in songs.withIndex()) {
                val sc = Matcher.score(song, title, artists, fileStem, duration, i)
                if (best == null || sc.score > bestScore) {
                    best = song
                    bestScore = sc.score
                    bestDetail = sc.detail
                }
            }
            if (best != null && bestScore >= 100) break   // 已经很有把握，不再放宽关键词
        }

        val chosen = best
        if (chosen == null || bestScore < opts.minScore) {
            rec.put("status", if (!gotResponse && error.isNotEmpty()) "error" else "no_match")
            if (chosen != null) {
                rec.put("score", Math.round(bestScore * 10) / 10.0)
                rec.put("candidate", chosen.toString())
            }
            if (!gotResponse && error.isNotEmpty()) rec.put("error", error)
            lock.withLock {
                if (rec.optString("status") == "error") stats.errors++ else stats.unmatched++
                cache.put(name, rec)
            }
            sink.log(
                "[%d/%d] ?  %s  未匹配%s %s".format(
                    index, total, name.take(40),
                    if (chosen != null) "(最高分 %.0f)".format(bestScore) else "",
                    rec.optString("error", "")
                )
            )
            return
        }

        rec.put("id", chosen.id)
        rec.put("score", Math.round(bestScore * 10) / 10.0)
        rec.put("detail", bestDetail)
        rec.put("matched", chosen.toString())
        rec.put("album", chosen.album)
        val notes = mutableListOf<String>()

        // ---- 歌词 ----
        if (wantLrc) {
            try {
                val r = Netease.getLyric(chosen.id, limiter)
                val text =
                    if (r.raw.isNotEmpty()) Lrc.merge(r.raw, r.translation, opts.translation) else ""
                if (text.isNotEmpty()) {
                    if (!opts.dryRun) {
                        withContext(Dispatchers.IO) {
                            val body = if (opts.lyricBom) "﻿" + text else text
                            lrcFile.writeText(body, Charsets.UTF_8)
                        }
                    }
                    rec.put("lyric", "ok")
                    val withTr = r.translation.isNotBlank() && opts.translation
                    notes.add("歌词OK" + if (withTr) "(含翻译)" else "")
                } else {
                    val note = r.note.ifEmpty { "歌词无时间轴" }
                    rec.put("lyric", note)
                    notes.add("歌词-$note")
                }
            } catch (e: Exception) {
                rec.put("lyric", "error: ${e.message}")
                notes.add("歌词-异常")
            }
        }

        // ---- 封面 ----
        if (wantCover) {
            try {
                val picUrl = chosen.picUrl.ifEmpty { Netease.getPicUrl(chosen.id, limiter) }
                if (picUrl.isNotEmpty()) {
                    val bytes = Netease.downloadCover(picUrl, opts.coverSize)
                    if (!opts.dryRun) {
                        withContext(Dispatchers.IO) { jpgFile.writeBytes(bytes) }
                    }
                    rec.put("cover", "ok")
                    notes.add("封面OK")
                } else {
                    rec.put("cover", "无封面地址")
                    notes.add("封面-无地址")
                }
            } catch (e: Exception) {
                rec.put("cover", "error: ${e.message}")
                notes.add("封面-异常")
            }
        }

        rec.put("status", "ok")
        lock.withLock {
            stats.matched++
            when (rec.optString("lyric")) {
                "ok" -> stats.lyrics++
                "纯音乐/无歌词" -> stats.noLyric++
            }
            if (rec.optString("cover") == "ok") stats.covers++
            cache.put(name, rec)
        }
        sink.log(
            "[%d/%d] %s %s -> %s [%s|%.0f] %s".format(
                index, total, if (bestScore >= 95) "OK" else "~ ",
                name.take(36), chosen.toString().take(30),
                bestDetail, bestScore, notes.joinToString(" ")
            )
        )
    }

    /** 把需要人工确认的条目写成一份文本报告，格式与桌面版一致。 */
    private fun writeReport(root: File, cache: JSONObject) {
        val bad = mutableListOf<JSONObject>()
        for (key in cache.keys()) {
            val v = cache.optJSONObject(key) ?: continue
            if (needsWork(v)) bad.add(v)
        }
        bad.sortBy { it.optString("file") }

        val sb = StringBuilder()
        sb.append("需要人工确认的条目：${bad.size} 条\n")
        sb.append("（可以手动改文件名成「歌手 - 歌名」后重跑，或用关键词单独处理）\n")
        sb.append("=".repeat(78)).append('\n')
        for (v in bad) {
            sb.append(v.optString("file")).append('\n')
            val matched = v.optString("matched").ifEmpty { v.optString("candidate").ifEmpty { "-" } }
            sb.append("    匹配: $matched (score=${v.opt("score")})\n")
            sb.append(
                "    歌词: ${v.optString("lyric", "-")}   封面: ${v.optString("cover", "-")}\n"
            )
            if (v.has("error")) sb.append("    错误: ${v.optString("error")}\n")
            sb.append('\n')
        }
        File(root, REPORT_NAME).writeText(sb.toString(), Charsets.UTF_8)
    }
}
