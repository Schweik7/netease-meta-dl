package com.schweik.nmdl.core

/**
 * LRC 解析与合成，对应桌面版的 `lrc.py`。
 *
 * 网易云的原文和翻译是两份独立的 LRC。合并时把翻译写成**同一时间戳的下一行**——
 * 这正是 MusicPlayer2 识别翻译的方式（相同时间戳的第二行视为上一行的译文），
 * 其它常见播放器也大多兼容。
 */
object Lrc {

    private val TIME_RE = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 一行歌词：毫秒 + 文本。 */
    data class Line(val ms: Int, val text: String)

    /** -> (元信息行, 按时间排序的歌词行)。 */
    fun parse(text: String?): Pair<List<String>, List<Line>> {
        val meta = mutableListOf<String>()
        val timed = mutableListOf<Line>()
        for (rawLine in (text ?: "").lines()) {
            val raw = rawLine.trim()
            if (raw.isEmpty()) continue
            val tags = TIME_RE.findAll(raw).toList()
            if (tags.isEmpty()) {
                if (raw.startsWith("[") && raw.endsWith("]")) meta.add(raw)
                continue
            }
            val content = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) {           // 一行可能挂多个时间戳（副歌复用）
                var ms = m.groupValues[1].toInt() * 60000 + m.groupValues[2].toInt() * 1000
                val frac = m.groupValues[3]
                if (frac.isNotEmpty()) ms += frac.padEnd(3, '0').substring(0, 3).toInt()
                timed.add(Line(ms, content))
            }
        }
        timed.sortBy { it.ms }
        return Pair(meta, timed)
    }

    fun fmtTs(ms: Int): String =
        "[%02d:%02d.%03d]".format(ms / 60000, (ms / 1000) % 60, ms % 1000)

    /** 合成最终 LRC 文本；没有时间轴则返回空字符串。 */
    fun merge(rawLyric: String, translation: String = "", withTranslation: Boolean = true): String {
        val (meta, timed) = parse(rawLyric)
        if (timed.isEmpty()) return ""

        val tmap = HashMap<Int, String>()
        if (withTranslation && translation.isNotEmpty()) {
            for (line in parse(translation).second) {
                if (line.text.isNotEmpty()) tmap[line.ms] = line.text
            }
        }
        val keys = tmap.keys.sorted()

        val out = mutableListOf<String>()
        out.addAll(meta)
        for ((ms, content) in timed) {
            out.add(fmtTs(ms) + content)
            var tr = tmap[ms]
            if (tr == null && keys.isNotEmpty()) {
                // 两份 LRC 的时间戳偶有毫秒级偏差，就近找一条
                val i = keys.binarySearchInsertionPoint(ms)
                for (j in intArrayOf(i - 1, i)) {
                    if (j in keys.indices && kotlin.math.abs(keys[j] - ms) <= 60) {
                        tr = tmap[keys[j]]
                        break
                    }
                }
            }
            if (tr != null && Matcher.norm(tr) != Matcher.norm(content)) out.add(fmtTs(ms) + tr)
        }
        return out.joinToString("\n") + "\n"
    }

    /** 等价于 Python 的 `bisect.bisect_left`：返回 value 应插入的下标。 */
    private fun List<Int>.binarySearchInsertionPoint(value: Int): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
