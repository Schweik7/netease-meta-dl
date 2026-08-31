package com.schweik.nmdl

import android.content.Context
import com.schweik.nmdl.core.Downloader

/**
 * 把界面上的设置存进 SharedPreferences，下次打开还是上次那套。
 *
 * 只存 [Downloader.Options] 里用户会调的字段；`dryRun` 不存——试运行是一次性的
 * 临时行为，下次打开默认关掉更安全。
 */
object Prefs {

    private const val FILE = "nmdl"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): Downloader.Options {
        val p = sp(ctx)
        val d = Downloader.Options()
        return d.copy(
            directory = p.getString("directory", "") ?: "",
            recursive = p.getBoolean("recursive", d.recursive),
            only = p.getString("only", "") ?: "",
            limit = p.getInt("limit", d.limit),
            force = p.getBoolean("force", d.force),
            retryFailed = p.getBoolean("retryFailed", d.retryFailed),
            workers = p.getInt("workers", d.workers),
            rps = p.getFloat("rps", d.rps.toFloat()).toDouble(),
            searchLimit = p.getInt("searchLimit", d.searchLimit),
            minScore = p.getFloat("minScore", d.minScore.toFloat()).toDouble(),
            lyric = p.getBoolean("lyric", d.lyric),
            cover = p.getBoolean("cover", d.cover),
            translation = p.getBoolean("translation", d.translation),
            coverSize = p.getInt("coverSize", d.coverSize),
            lyricBom = p.getBoolean("lyricBom", d.lyricBom),
        )
    }

    fun save(ctx: Context, o: Downloader.Options) {
        sp(ctx).edit().apply {
            putString("directory", o.directory)
            putBoolean("recursive", o.recursive)
            putString("only", o.only)
            putInt("limit", o.limit)
            putBoolean("force", o.force)
            putBoolean("retryFailed", o.retryFailed)
            putInt("workers", o.workers)
            putFloat("rps", o.rps.toFloat())
            putInt("searchLimit", o.searchLimit)
            putFloat("minScore", o.minScore.toFloat())
            putBoolean("lyric", o.lyric)
            putBoolean("cover", o.cover)
            putBoolean("translation", o.translation)
            putInt("coverSize", o.coverSize)
            putBoolean("lyricBom", o.lyricBom)
        }.apply()
    }
}
