package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WindowMath` + `TimeWindow.contains` 的纯逻辑单测。
 *
 * 为什么要测这两块一起：家长在电视上敲的是「时段」，孩子被拦住的那一刻靠的是
 * `TimeWindow.contains` —— 一个负责把「1800-2000」变成 1080..1200，一个负责判断现在能不能看。
 * 中间任何一步错了，表现都是「明明设了时段却让看」或「明明在时段内却被锁」，
 * 而这台电视上很难端到端构造（改系统时钟会被 ROM 校回去，见 docs/待实机验证.md）。
 *
 * 全部是纯 JVM：不碰 `android.*`、不碰 `org.json`。
 */
class WindowMathTest {

    // 「全天」在界面上有两种写法，由调用方从 strings.xml 传进来
    private val allDay = listOf("全天", "全天不限")

    private fun w(start: String, end: String): TimeWindow =
        TimeWindow(minOf(start), minOf(end))

    private fun minOf(s: String): Int =
        s.substringBefore(':').toInt() * 60 + s.substringAfter(':').toInt()

    // ---------------------------------------------------------------- 解析

    @Test
    fun `四种常见写法都认`() {
        val expect = listOf(w("18:00", "20:00"))
        assertEquals(expect, WindowMath.parse("18:00-20:00"))
        assertEquals(expect, WindowMath.parse("1800-2000"))
        assertEquals(expect, WindowMath.parse("  1800 - 2000  "))
    }

    @Test
    fun `全角冒号与中文分隔符都认`() {
        val expect = listOf(w("18:00", "20:00"))
        assertEquals(expect, WindowMath.parse("18：00－20：00"))
        assertEquals(expect, WindowMath.parse("18:00至20:00"))
        assertEquals(expect, WindowMath.parse("18:00~20:00"))
        assertEquals(expect, WindowMath.parse("18:00–20:00")) // en dash
        assertEquals(expect, WindowMath.parse("18:00—20:00")) // em dash
    }

    @Test
    fun `小时只有一位也认`() {
        assertEquals(listOf(w("07:30", "11:30")), WindowMath.parse("7:30-11:30"))
        assertEquals(listOf(w("07:30", "11:30")), WindowMath.parse("730-1130"))
    }

    @Test
    fun `多段用逗号顿号空格换行分开都行`() {
        val expect = listOf(w("09:00", "11:30"), w("16:00", "21:00"))
        assertEquals(expect, WindowMath.parse("09:00-11:30,16:00-21:00"))
        assertEquals(expect, WindowMath.parse("0900-1130、1600-2100"))
        assertEquals(expect, WindowMath.parse("0900-1130  1600-2100"))
        assertEquals(expect, WindowMath.parse("0900-1130\n1600-2100"))
    }

    @Test
    fun `空着就是全天`() {
        assertEquals(emptyList<TimeWindow>(), WindowMath.parse(""))
        assertEquals(emptyList<TimeWindow>(), WindowMath.parse("   "))
    }

    @Test
    fun `写成全天两个字也是全天`() {
        assertEquals(emptyList<TimeWindow>(), WindowMath.parse("全天", allDay))
        assertEquals(emptyList<TimeWindow>(), WindowMath.parse("全天不限", allDay))
        // 不带文案的时候就认不出来 —— 说明「全天」不是硬编码在这里的
        assertNull(WindowMath.parse("全天"))
    }

    @Test
    fun `乱写的返回 null 而不是空列表`() {
        // 关键区别：空列表 = 全天（放开），null = 格式不对（不写回）。
        // 两者搞混的后果是家长敲错了字，孩子的时段被静默改成「全天」
        assertNull(WindowMath.parse("随便看看"))
        assertNull(WindowMath.parse("18-20")) // 分钟必须补齐
        assertNull(WindowMath.parse("-"))
        assertNull(WindowMath.parse("18:00-")) // 缺结束时间
    }

    @Test
    fun `越界的时间夹到合法范围而不是抛异常`() {
        assertEquals(listOf(w("23:00", "23:00")), WindowMath.parse("25:00-26:00"))
        assertEquals(listOf(w("18:59", "19:00")), WindowMath.parse("18:99-19:00"))
    }

    // ------------------------------------------------------------ 显示与写回

    @Test
    fun `显示用中文顿号连接 空列表显示全天`() {
        assertEquals("全天", WindowMath.text(emptyList(), "全天"))
        assertEquals(
            "18:00-20:00、21:00-21:30",
            WindowMath.text(listOf(w("18:00", "20:00"), w("21:00", "21:30")), "全天")
        )
        assertEquals(
            "18:00-20:00 / 21:00-21:30",
            WindowMath.text(listOf(w("18:00", "20:00"), w("21:00", "21:30")), "全天", " / ")
        )
    }

    @Test
    fun `补零显示 不出现九点零分写成 9点0分`() {
        assertEquals("07:05", WindowMath.hhmm(7 * 60 + 5))
        assertEquals("00:00", WindowMath.hhmm(0))
        assertEquals("23:59", WindowMath.hhmm(23 * 60 + 59))
        assertEquals("0705", WindowMath.compactHm(7 * 60 + 5))
    }

    @Test
    fun `写回 tv_json 用补零的写法`() {
        assertEquals(
            listOf("18:00-20:00", "21:00-21:30"),
            WindowMath.toJsonStrings(listOf(w("18:00", "20:00"), w("21:00", "21:30")))
        )
        assertEquals(emptyList<String>(), WindowMath.toJsonStrings(emptyList()))
    }

    @Test
    fun `输入框预填是紧凑写法`() {
        assertEquals(
            "1800-2000,2100-2130",
            WindowMath.compact(listOf(w("18:00", "20:00"), w("21:00", "21:30")))
        )
    }

    // ---------------------------------------------------------------- 往返

    @Test
    fun `紧凑写法往返不失真`() {
        val list = listOf(w("09:00", "11:30"), w("16:00", "21:00"))
        assertEquals(list, WindowMath.parse(WindowMath.compact(list)))
    }

    @Test
    fun `显示文字再喂回解析也不失真`() {
        // 家长看到的「18:00-20:00、21:00-21:30」正是他自己的输入，改一个字再存回去
        val list = listOf(w("18:00", "20:00"), w("21:00", "21:30"))
        assertEquals(list, WindowMath.parse(WindowMath.text(list, "全天")))
    }

    // ---------------------------------------------------------------- 跨零点

    @Test
    fun `普通时段的边界是左闭右开`() {
        val win = w("18:00", "20:00")
        assertFalse(win.contains(minOf("17:59")))
        assertTrue(win.contains(minOf("18:00")))
        assertTrue(win.contains(minOf("19:59")))
        // 20:00 整不算：否则「到点」那一刻会有一分钟白送的窗口
        assertFalse(win.contains(minOf("20:00")))
        assertFalse(win.contains(minOf("20:01")))
    }

    @Test
    fun `跨零点的时段管到第二天凌晨`() {
        val win = w("22:00", "01:00")
        assertFalse(win.contains(minOf("21:59")))
        assertTrue(win.contains(minOf("22:00")))
        assertTrue(win.contains(minOf("23:59")))
        assertTrue(win.contains(minOf("00:00")))
        assertTrue(win.contains(minOf("00:59")))
        assertFalse(win.contains(minOf("01:00")))
        assertFalse(win.contains(minOf("12:00")))
    }

    @Test
    fun `起止相同按全天算`() {
        // 家长把两段写成一样的时间时，本意绝不会是「一分钟都不许看」；
        // 既有语义就是全天（endMin <= startMin 走跨零点分支，且 start 也算命中），单测把它钉住
        val win = w("22:00", "22:00")
        assertTrue(win.contains(minOf("00:00")))
        assertTrue(win.contains(minOf("12:00")))
        assertTrue(win.contains(minOf("21:59")))
        assertTrue(win.contains(minOf("22:00"))) // 起止相同的退化情形，连起点也算在内
    }

    @Test
    fun `多段时段任一命中即放行`() {
        val list = WindowMath.parse("09:00-11:30,16:00-21:00")!!
        fun open(t: String) = list.any { it.contains(minOf(t)) }
        assertTrue(open("09:00"))
        assertTrue(open("16:30"))
        assertFalse(open("12:00"))
        assertFalse(open("21:30"))
    }
}
