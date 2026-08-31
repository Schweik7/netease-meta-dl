package com.schweik.nmdl.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import java.io.File

/**
 * 扫描音乐文件、读取时长，对应桌面版的 `core.collect` 和 `tagging.duration_of`。
 *
 * 时长在打分里权重最高（对得上直接 +45 分），但逐个文件跑
 * [MediaMetadataRetriever] 很慢——几百首要几十秒。系统媒体库其实早就索引过了，
 * 所以先一次性把 MediaStore 里的时长拉成一张表，查不到的才回退到逐个读取。
 */
object Scanner {

    val AUDIO_EXT = setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "wma", "ape", "aac")

    /** 按目录/递归/关键词筛出音乐文件，排序后返回。 */
    fun collect(
        root: File,
        recursive: Boolean,
        only: String = "",
        exts: Set<String> = AUDIO_EXT,
    ): List<File> {
        val out = mutableListOf<File>()

        fun accept(f: File) {
            if (f.isFile && f.extension.lowercase() in exts) out.add(f)
        }

        if (recursive) {
            // 跳过隐藏目录，跟桌面版 os.walk 里那句 dirnames 过滤对齐
            root.walkTopDown()
                .onEnter { !it.name.startsWith(".") }
                .forEach { accept(it) }
        } else {
            root.listFiles()?.forEach { accept(it) }
        }

        var files: List<File> = out
        if (only.isNotEmpty()) {
            val needle = only.lowercase()
            files = files.filter { it.name.lowercase().contains(needle) }
        }
        return files.sortedBy { it.absolutePath }
    }

    /** 从系统媒体库拉一张「绝对路径（小写）-> 时长秒」的表。读不到就返回空表。 */
    fun durationMap(context: Context): Map<String, Double> {
        val map = HashMap<String, Double>()
        val proj = arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION)
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null
            )?.use { c ->
                val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val path = c.getString(iPath) ?: continue
                    val ms = c.getLong(iDur)
                    if (ms > 0) map[path.lowercase()] = ms / 1000.0
                }
            }
        } catch (_: Exception) {
            // 没权限或厂商 ROM 行为异常，退回逐个读取
        }
        return map
    }

    /** 单个文件的时长（秒）；读不出来返回 0，打分时会自动跳过时长这一项。 */
    fun durationOf(file: File, cache: Map<String, Double> = emptyMap()): Double {
        cache[file.absolutePath.lowercase()]?.let { return it }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ms?.toLongOrNull()?.let { it / 1000.0 } ?: 0.0
        } catch (_: Exception) {
            0.0
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }
}
