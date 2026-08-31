package com.schweik.nmdl.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 跑在真机上的回归测试。
 *
 * 存在的理由：安卓的正则是 **ICU** 实现，JVM 的是 `java.util.regex`，两者语法并不全等。
 * 本项目就踩过一次——`(?U)` 内联标志 JVM 认、ICU 不认，于是 `app/src/test` 里的
 * 单元测试全绿，装到手机上一点「开始」就崩在 [Matcher] 的类初始化里。
 *
 * 所以凡是涉及正则的行为，除了 JVM 单元测试，这里再用同样的期望值验一遍。
 * 跑法：`gradlew connectedDebugAndroidTest`（需要连着设备）。
 */
@RunWith(AndroidJUnit4::class)
class MatcherDeviceTest {

    @Test
    fun 正则在设备上能正常构造并归一化() {
        // 只要 Matcher 的静态初始化里有 ICU 不认的语法，这一行就会抛异常
        assertEquals("私有黄昏", Matcher.norm("私有黄昏 (Live)"))
        assertEquals("shapeofyou", Matcher.norm("Shape of You [Official Audio]"))
        assertEquals("abcd", Matcher.norm("A-B_C·D"))
        // 中文夹着的 live 不算独立单词，不能删——这正是当初要用 (?U) 的原因
        assertEquals("演唱会live版", Matcher.norm("演唱会live版"))
    }

    @Test
    fun 相似度在设备上与桌面版一致() {
        assertEquals(1.0, Matcher.sim("轻舞（Moonlit paths heart follows）", "轻舞"), 1e-6)
        assertEquals(0.75, Matcher.sim("告白气球", "告白汽球"), 1e-6)
        assertEquals(0.2, Matcher.sim("hello", "world"), 1e-6)
        assertEquals(0.0, Matcher.sim("Lemon", "レモン"), 1e-6)
    }

    @Test
    fun 文件名拆分在设备上与桌面版一致() {
        assertEquals(Pair(listOf("陈麒名"), "私有黄昏"), Matcher.splitName("陈麒名 - 私有黄昏"))
        assertEquals(
            Pair(listOf("HOYO-MiX"), "璃月 Liyue"),
            Matcher.splitName("HOYO-MiX - 璃月 Liyue")
        )
        assertEquals(
            Pair(listOf("周杰伦", "方文山"), "青花瓷 - 副标题"),
            Matcher.splitName("周杰伦,方文山 - 青花瓷 - 副标题")
        )
    }

    @Test
    fun 打分在设备上与桌面版一致() {
        val stem = "陈麒名 - 私有黄昏"
        val (artists, title) = Matcher.splitName(stem)
        val song = Song(1, "私有黄昏", listOf("陈麒名"), duration = 142.5)
        val r = Matcher.score(song, title, artists, stem, 142.0, 0)
        assertEquals(138.0, r.score, 1e-6)
        assertEquals("title=,artist=,dur<2s", r.detail)
    }

    @Test
    fun 歌词合并在设备上与桌面版一致() {
        val raw = "[00:01.00]第一句\n[00:05.50]第二句"
        val tra = "[00:01.00]First\n[00:05.55]Second"     // 故意差 50ms
        assertEquals(
            "[00:01.000]第一句\n[00:01.000]First\n[00:05.500]第二句\n[00:05.500]Second\n",
            Lrc.merge(raw, tra, true)
        )
    }
}
