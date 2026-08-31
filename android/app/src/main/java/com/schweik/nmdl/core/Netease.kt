package com.schweik.nmdl.core

import org.json.JSONArray
import org.json.JSONObject

/** 一首网易云歌曲的搜索结果。 */
data class Song(
    val id: Long,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val duration: Double = 0.0,     // 秒
    val picUrl: String = "",
) {
    val artist: String get() = artists.joinToString("/")
    override fun toString(): String = "$artist - $title"
}

/**
 * 网易云接口封装，对应桌面版的 `netease.py`。
 *
 * 接口地址与 MusicPlayer2 的 `NeteaseLyricDownload.cpp` 一致：
 *
 * * 搜索   `/api/search/get/?s=&limit=&type=1&offset=0`
 * * 歌词   `/api/song/lyric?os=osx&id=&lv=-1&kv=-1&tv=-1`
 * * 封面   `/api/song/detail/?id=&ids=[]` 里的 `picUrl`
 *
 * 额外用了 `/api/cloudsearch/pc`：返回结构里直接带 `al.picUrl`，命中时可以省掉
 * 一次 detail 请求（请求数少三分之一，也就更不容易撞限流）。
 */
object Netease {

    private const val CLOUDSEARCH_URL = "https://music.163.com/api/cloudsearch/pc"
    private const val SEARCH_URL = "https://music.163.com/api/search/get/"
    private const val LYRIC_URL = "https://music.163.com/api/song/lyric"
    private const val DETAIL_URL = "https://music.163.com/api/song/detail/"

    private fun JSONArray.names(): List<String> = buildList {
        for (i in 0 until this@names.length()) {
            val n = this@names.optJSONObject(i)?.optString("name").orEmpty()
            if (n.isNotEmpty()) add(n)
        }
    }

    private fun fromCloudsearch(d: JSONObject): Song {
        val al = d.optJSONObject("al")
        return Song(
            id = d.optLong("id"),
            title = d.optString("name"),
            artists = d.optJSONArray("ar")?.names() ?: emptyList(),
            album = al?.optString("name").orEmpty(),
            duration = d.optLong("dt") / 1000.0,
            picUrl = al?.optString("picUrl").orEmpty(),
        )
    }

    private fun fromSearch(d: JSONObject): Song {
        val al = d.optJSONObject("album")
        return Song(
            id = d.optLong("id"),
            title = d.optString("name"),
            artists = d.optJSONArray("artists")?.names() ?: emptyList(),
            album = al?.optString("name").orEmpty(),
            duration = d.optLong("duration") / 1000.0,
            picUrl = al?.optString("picUrl").orEmpty(),
        )
    }

    /** 搜索歌曲。优先用 cloudsearch（带封面地址），失败时退回旧接口。 */
    suspend fun search(
        keyword: String,
        limit: Int = 15,
        limiter: Http.RateLimiter? = null,
    ): List<Song> {
        val params = mapOf<String, Any>(
            "s" to keyword, "type" to 1, "offset" to 0, "total" to "true", "limit" to limit
        )
        try {
            val j = Http.apiJson(CLOUDSEARCH_URL, params, limiter)
            val songs = j.optJSONObject("result")?.optJSONArray("songs")
            if (songs != null && songs.length() > 0) {
                return (0 until songs.length()).mapNotNull {
                    songs.optJSONObject(it)?.let(::fromCloudsearch)
                }
            }
        } catch (_: Exception) {
            // 交给下面的旧接口
        }
        val j = Http.apiJson(SEARCH_URL, params, limiter)
        val songs = j.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).mapNotNull { songs.optJSONObject(it)?.let(::fromSearch) }
    }

    /** 歌词结果：原文 / 翻译 / 说明（拿不到时 raw 为空）。 */
    data class LyricResult(val raw: String, val translation: String, val note: String)

    suspend fun getLyric(songId: Long, limiter: Http.RateLimiter? = null): LyricResult {
        val j = Http.apiJson(
            LYRIC_URL,
            mapOf("os" to "osx", "id" to songId, "lv" to -1, "kv" to -1, "tv" to -1),
            limiter,
        )
        if (j.optBoolean("nolyric")) return LyricResult("", "", "纯音乐/无歌词")
        if (j.optBoolean("uncollected")) return LyricResult("", "", "暂无歌词收录")
        val raw = j.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val tra = j.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        if (raw.isBlank()) return LyricResult("", "", "歌词为空")
        return LyricResult(raw, tra, "")
    }

    /** 搜索结果里没带封面地址时，用歌曲详情接口补一次。 */
    suspend fun getPicUrl(songId: Long, limiter: Http.RateLimiter? = null): String {
        val j = Http.apiJson(
            DETAIL_URL,
            mapOf("id" to songId, "ids" to "[$songId]", "csrf_token" to ""),
            limiter,
        )
        val songs = j.optJSONArray("songs") ?: return ""
        val album = songs.optJSONObject(0)?.optJSONObject("album") ?: return ""
        return album.optString("picUrl").ifEmpty { album.optString("blurPicUrl") }
    }

    /** 按指定边长下载封面。图片走 CDN，不占接口限流额度。 */
    suspend fun downloadCover(picUrl: String, size: Int = 800): ByteArray {
        var url = picUrl
        if (url.startsWith("http://")) url = "https://" + url.substring(7)
        if (size > 0) url = "$url?param=${size}y$size"
        val data = Http.getBytes(url)
        if (data.size < 2048) throw RuntimeException("封面数据过小(${data.size} 字节)")
        return data
    }
}
