package com.schweik.nmdl.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 和桌面版 `src/nmdl/lrc.py` 对拍，期望值同样是从 CPython 跑出来的。 */
class LrcTest {

    private val raw = """
        [ti:测试歌]
        [ar:某人]
        [00:01.00]第一句
        [00:05.50]第二句
        [00:09.123]第三句
        [00:12.00][00:20.00]重复句
    """.trimIndent()

    // 故意让第二、三句的时间戳和原文差几十毫秒，考察就近匹配
    private val translation = """
        [00:01.00]First line
        [00:05.55]Second line
        [00:09.10]Third line
    """.trimIndent()

    @Test
    fun `时间戳格式化`() {
        assertEquals("[00:09.123]", Lrc.fmtTs(9123))
        assertEquals("[00:00.000]", Lrc.fmtTs(0))
        assertEquals("[10:05.500]", Lrc.fmtTs(605500))
    }

    @Test
    fun `解析出元信息和按时间排序的歌词`() {
        val (meta, timed) = Lrc.parse(raw)
        assertEquals(listOf("[ti:测试歌]", "[ar:某人]"), meta)
        assertEquals(
            listOf(
                Lrc.Line(1000, "第一句"),
                Lrc.Line(5500, "第二句"),
                Lrc.Line(9123, "第三句"),
                Lrc.Line(12000, "重复句"),   // 一行两个时间戳，展开成两条
                Lrc.Line(20000, "重复句"),
            ),
            timed
        )
    }

    @Test
    fun `合并翻译时容忍毫秒级偏差`() {
        val expected = "[ti:测试歌]\n[ar:某人]\n" +
            "[00:01.000]第一句\n[00:01.000]First line\n" +
            "[00:05.500]第二句\n[00:05.500]Second line\n" +   // 原文 5500 / 译文 5550
            "[00:09.123]第三句\n[00:09.123]Third line\n" +    // 原文 9123 / 译文 9100
            "[00:12.000]重复句\n[00:20.000]重复句\n"
        assertEquals(expected, Lrc.merge(raw, translation, true))
    }

    @Test
    fun `关掉翻译就只留原文`() {
        val expected = "[ti:测试歌]\n[ar:某人]\n" +
            "[00:01.000]第一句\n[00:05.500]第二句\n[00:09.123]第三句\n" +
            "[00:12.000]重复句\n[00:20.000]重复句\n"
        assertEquals(expected, Lrc.merge(raw, translation, false))
    }

    @Test
    fun `没有时间轴就返回空串`() {
        assertEquals("", Lrc.merge("这是一段没有时间戳的文字", "", true))
        assertEquals("", Lrc.merge("", "", true))
    }
}
