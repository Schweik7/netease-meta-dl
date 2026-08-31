package com.schweik.nmdl.core

import java.text.Normalizer

/**
 * 从文件名解析曲目信息，并对搜索结果打分选出最佳匹配。
 *
 * 逐条对应桌面版的 `matcher.py`：打分权重照搬 MusicPlayer2 的
 * `CLyricDownloadCommon::SelectMatchedItem`，标题 0.4 / 艺术家 0.4 /
 * 文件名↔标题 0.3 / 列表排序 0.05（统一放大 100 倍），另外补上时长判定——
 * 本地文件的时长是最硬的证据，能有效排掉同名的现场版、翻唱和重制版。
 *
 * 两处必须和 Python 对齐的细节：
 *  1. 相似度用 Ratcliff/Obershelp（见 [SequenceMatcher]），不是编辑距离；
 *     换算法会让分数整体漂移，`minScore` 的含义就变了。
 *  2. 词边界不能直接写 `\b`。Java 的 `\w` 只认 ASCII，而 Python 的 `\w` 含中文，
 *     照抄 `\b` 会把「演唱会live版」里的 live 当噪声词删掉，和桌面版对不上。
 *     JVM 上可以用 `(?U)` 修正，但**安卓的正则是 ICU 实现，不认这个内联标志**，
 *     `Regex(...)` 一构造就抛 PatternSyntaxException——而单元测试跑在 JVM 上，
 *     用的是 OpenJDK 的 java.util.regex，照样能过。所以这里用 lookaround 把
 *     Unicode 词边界显式写出来，两个平台的行为才真的一致。
 */
object Matcher {

    /** Unicode 语义下的「词字符」，等价于 Python 里的 `\w`。 */
    private const val W = """[\p{L}\p{N}_]"""

    private val BRACKETS = Regex("""[（(\[【].*?[)）\]】]""")
    private val NOISE = Regex(
        """(?i)(?<!$W)(official|audio|video|mv|hd|hq|remaster(ed)?|radio\s*edit|live|""" +
            """inst(rumental)?|cover|feat\.?|ft\.?)(?!$W)"""
    )
    private val PUNCT =
        Regex("""[\s\-_·,，.。!！?？'"“”‘’:：;；/\\|~～&+*^%${'$'}#@()（）\[\]【】{}<>《》]+""")
    private val ARTIST_SEP =
        Regex("""(?i)[,，/&;、]|(?<!$W)feat\.?(?!$W)|(?<!$W)ft\.?(?!$W)""")

    /** 比较用的强归一化：全角转半角、去括号内容、去噪声词、去标点、转小写。 */
    fun norm(s: String?): String {
        var t = Normalizer.normalize(s ?: "", Normalizer.Form.NFKC).lowercase()
        t = BRACKETS.replace(t, " ")
        t = NOISE.replace(t, " ")
        return PUNCT.replace(t, "")
    }

    /** 归一化后的字符串相似度，等价于桌面版的 `matcher.sim`。 */
    fun sim(a: String?, b: String?): Double {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        return SequenceMatcher.ratio(x, y)
    }

    /** 搜索用的宽松标题：去掉括号里的补充说明（remix / live / 电影版之类）。 */
    fun cleanTitle(t: String?): String {
        val c = BRACKETS.replace(Normalizer.normalize(t ?: "", Normalizer.Form.NFKC), " ").trim()
        return if (c.length >= 2) c else (t ?: "").trim()
    }

    /**
     * `"歌手1,歌手2 - 歌名 - 副标题"` -> `(["歌手1","歌手2"], "歌名 - 副标题")`
     *
     * 按第一个分隔符切分，副标题保留在标题里（网易云的曲名本身常带 `-`）。
     */
    fun splitName(stem: String): Pair<List<String>, String> {
        for (sep in listOf(" - ", " – ", " — ", " _ ", "-")) {
            val idx = stem.indexOf(sep)
            if (idx < 0) continue
            val left = stem.substring(0, idx).trim()
            val right = stem.substring(idx + sep.length).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                val artists = ARTIST_SEP.split(left).map { it.trim() }.filter { it.isNotEmpty() }
                return Pair(artists.ifEmpty { listOf(left) }, right)
            }
        }
        return Pair(emptyList(), stem.trim())
    }

    /** 打分结果：分数 + 命中项说明（写进缓存和日志里，方便事后核对）。 */
    data class Scored(val score: Double, val detail: String)

    /** 给一个搜索结果打分。满分约 138。 */
    fun score(
        song: Song,
        title: String,
        artists: List<String>,
        stem: String,
        duration: Double,
        index: Int = 0,
    ): Scored {
        val detail = mutableListOf<String>()
        var s = 0.0

        val ts = sim(title, song.title)
        s += ts * 40
        if (ts > 0.95) detail.add("title=") else if (ts > 0.6) detail.add("title~")

        var artistSim = sim(artists.joinToString("/"), song.artist)
        if (artists.isNotEmpty() && song.artists.isNotEmpty() && artistSim < 0.6) {
            // 多歌手时顺序/数量常对不上，退而求其次：只要有一个歌手对上就算
            artistSim = artists.maxOf { a -> song.artists.maxOf { sa -> sim(a, sa) } }
        }
        s += artistSim * 30
        if (artistSim > 0.9) detail.add("artist=") else if (artistSim > 0.6) detail.add("artist~")

        s += sim(stem, "${song.artist} ${song.title}") * 20
        s += (1 - index * 0.02) * 3     // 网易云返回结果越靠前，关联度一般越高

        if (duration > 0 && song.duration > 0) {
            val diff = kotlin.math.abs(song.duration - duration)
            when {
                diff <= 2 -> { s += 45; detail.add("dur<2s") }
                diff <= 5 -> { s += 32; detail.add("dur<5s") }
                diff <= 10 -> { s += 14; detail.add("dur<10s") }
                diff > 25 -> { s -= 35; detail.add("dur!!") }
                else -> s -= 8
            }
        }
        return Scored(s, detail.joinToString(","))
    }

    /** 搜索关键词，从最精确逐步放宽。 */
    fun keywordsFor(title: String, artists: List<String>): List<String> {
        val main = artists.firstOrNull() ?: ""
        val ct = cleanTitle(title)
        val out = mutableListOf<String>()
        for (raw in listOf("$main $title", "$main $ct", title, ct)) {
            val k = raw.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
            if (k.isNotEmpty() && k !in out) out.add(k)
        }
        return out
    }
}

/**
 * Python `difflib.SequenceMatcher.ratio()` 的等价实现（Ratcliff/Obershelp）。
 *
 * 递归地找最长公共子串，再对它左右两侧各自重复，最后
 * `ratio = 2 * 匹配字符总数 / 两串长度和`。注意这跟编辑距离不是一回事。
 *
 * 没有实现 difflib 的 autojunk 启发式——它只在序列长度 >= 200 时才生效，
 * 而这里比的是歌名和歌手名，够不着。
 */
private object SequenceMatcher {

    fun ratio(a: String, b: String): Double {
        val total = a.length + b.length
        if (total == 0) return 1.0
        // b 里每个字符出现的所有位置，供 findLongestMatch 快速定位
        val b2j = HashMap<Char, MutableList<Int>>()
        for ((j, ch) in b.withIndex()) b2j.getOrPut(ch) { mutableListOf() }.add(j)
        val matches = matchCount(a, 0, a.length, b, 0, b.length, b2j)
        return 2.0 * matches / total
    }

    private fun matchCount(
        a: String, alo: Int, ahi: Int,
        b: String, blo: Int, bhi: Int,
        b2j: Map<Char, MutableList<Int>>,
    ): Int {
        val m = findLongestMatch(a, alo, ahi, b, blo, bhi, b2j)
        if (m.size == 0) return 0
        return m.size +
            matchCount(a, alo, m.i, b, blo, m.j, b2j) +
            matchCount(a, m.i + m.size, ahi, b, m.j + m.size, bhi, b2j)
    }

    private class Match(val i: Int, val j: Int, val size: Int)

    /**
     * 与 difflib 同款的滚动 DP：`j2len[j]` 表示「以 a[i-1] 和 b[j] 结尾的公共子串长度」。
     * 平局时取最靠前的一段，和 Python 的返回值保持一致。
     */
    private fun findLongestMatch(
        a: String, alo: Int, ahi: Int,
        b: String, blo: Int, bhi: Int,
        b2j: Map<Char, MutableList<Int>>,
    ): Match {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var j2len = HashMap<Int, Int>()

        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            for (j in b2j[a[i]] ?: emptyList<Int>()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newj2len[j] = k
                if (k > bestsize) {
                    besti = i - k + 1
                    bestj = j - k + 1
                    bestsize = k
                }
            }
            j2len = newj2len
        }
        return Match(besti, bestj, bestsize)
    }
}
