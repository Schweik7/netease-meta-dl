package com.schweik.nmdl.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 和桌面版对拍。
 *
 * 期望值全部由 `src/nmdl/matcher.py` 在 CPython 3.13 上跑出来，不是手算的——
 * 这套分数直接决定「匹配 / 未匹配」的分界线，两端必须给出同样的结果。
 */
class MatcherTest {

    private fun assertSim(expected: Double, a: String, b: String) {
        assertEquals("sim($a, $b)", expected, Matcher.sim(a, b), 1e-6)
    }

    @Test
    fun `相似度与 Python difflib 一致`() {
        assertSim(1.0, "私有黄昏", "私有黄昏")
        assertSim(1.0, "轻舞（Moonlit paths heart follows）", "轻舞")   // 括号内容不参与比较
        assertSim(1.0, "无涯（TV size）", "无涯")
        assertSim(1.0, "Shape of You", "Shape of You (Acoustic)")
        assertSim(1.0, "千与千寻", "千与千寻 (Live)")
        assertSim(1.0, "Faded", "Faded (Restrung)")
        assertSim(0.0, "Lemon", "レモン")
        assertSim(0.2, "hello", "world")
        assertSim(0.6, "陈奕迅/王菲", "王菲/陈奕迅")
        assertSim(2.0 / 3, "起风了", "风起了")
        assertSim(2.0 / 3, "周杰伦", "周杰倫")
        assertSim(0.75, "告白气球", "告白汽球")
        assertSim(6.0 / 7, "abcdefg", "acbdefg")
        assertSim(0.9333333, "夜空中最亮的星", "夜空中最亮的星星")
    }

    @Test
    fun `归一化与 Python 一致`() {
        assertEquals("私有黄昏", Matcher.norm("私有黄昏 (Live)"))
        assertEquals("shapeofyou", Matcher.norm("Shape of You [Official Audio]"))
        assertEquals("abcd", Matcher.norm("A-B_C·D"))
        // 关键回归点：Java 的 \w 默认只认 ASCII，不加 (?U) 这里的 live 会被当噪声删掉，
        // 而 Python 的 \w 含中文，不会删。删了就和桌面版对不上了。
        assertEquals("演唱会live版", Matcher.norm("演唱会live版"))
    }

    @Test
    fun `文件名拆分与 Python 一致`() {
        assertEquals(Pair(listOf("陈麒名"), "私有黄昏"), Matcher.splitName("陈麒名 - 私有黄昏"))
        // " - " 优先于 "-"，所以歌手名里的连字符不会被拆开
        assertEquals(
            Pair(listOf("HOYO-MiX"), "璃月 Liyue"),
            Matcher.splitName("HOYO-MiX - 璃月 Liyue")
        )
        assertEquals(
            Pair(listOf("周杰伦", "方文山"), "青花瓷 - 副标题"),
            Matcher.splitName("周杰伦,方文山 - 青花瓷 - 副标题")
        )
        assertEquals(Pair(emptyList<String>(), "无分隔符歌名"), Matcher.splitName("无分隔符歌名"))
    }

    @Test
    fun `搜索关键词从精确到宽松`() {
        val (artists, title) = Matcher.splitName("陈抒妮 - 轻舞（Moonlit paths heart follows）")
        assertEquals(
            listOf(
                "陈抒妮 轻舞（Moonlit paths heart follows）",
                "陈抒妮 轻舞",
                "轻舞（Moonlit paths heart follows）",
                "轻舞",
            ),
            Matcher.keywordsFor(title, artists)
        )
    }

    @Test
    fun `时长对得上就该压过同名的其它版本`() {
        val stem = "陈麒名 - 私有黄昏"
        val (artists, title) = Matcher.splitName(stem)
        val duration = 142.0

        val right = Song(1, "私有黄昏", listOf("陈麒名"), duration = 142.5)
        val live = Song(2, "私有黄昏 (Live)", listOf("陈麒名"), duration = 210.0)

        val sRight = Matcher.score(right, title, artists, stem, duration, 0)
        val sLive = Matcher.score(live, title, artists, stem, duration, 1)

        // 标题和歌手都对上、时长只差 0.5 秒，正好是满分
        assertEquals(138.0, sRight.score, 1e-6)
        assertEquals("title=,artist=,dur<2s", sRight.detail)
        // Live 版标题歌手同样对得上，仅靠时长差就被压到 58 分左右
        assertEquals(57.94, sLive.score, 1e-6)
        assertEquals("title=,artist=,dur!!", sLive.detail)
        assertTrue("时长吻合的应当得分更高", sRight.score > sLive.score)
    }

    @Test
    fun `多歌手对不齐时靠单个歌手兜底`() {
        // 文件名里三个歌手、网易云只标了一个，整串相似度掉到 0.5，
        // 于是走「只要有一个歌手对上就算」那条兜底，最终判成歌手完全匹配。
        val stem = "周杰伦,方文山,林俊杰 - 某歌"
        val (artists, title) = Matcher.splitName(stem)
        val song = Song(3, "某歌", listOf("林俊杰"))
        val r = Matcher.score(song, title, artists, stem, 0.0, 0)
        assertEquals(85.5, r.score, 1e-6)
        assertEquals("title=,artist=", r.detail)
    }

    @Test
    fun `歌手相似度正好卡在阈值上时不算命中`() {
        // 两个歌手顺序颠倒，相似度不多不少正好 0.6：
        // 兜底的条件是「< 0.6」、标注的条件是「> 0.6」，两边都差这一点点。
        // 这个边界和桌面版一致，日后动打分逻辑时这里会先报警。
        val stem = "陈奕迅,王菲 - 因为爱情"
        val (artists, title) = Matcher.splitName(stem)
        val song = Song(4, "因为爱情", listOf("王菲", "陈奕迅"))
        val r = Matcher.score(song, title, artists, stem, 0.0, 0)
        assertEquals(76.55555555555556, r.score, 1e-9)
        assertEquals("title=", r.detail)
    }
}
