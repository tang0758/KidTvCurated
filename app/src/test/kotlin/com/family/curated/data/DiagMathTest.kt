package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPT-17 诊断日志的纯逻辑：文件名、过期、200 KB 滚动、格式化、去重 */
class DiagMathTest {

    private val today = ReportMath.toEpochDay(2026, 11, 11)

    // ---------------- 文件名 ----------------

    @Test
    fun `日期转成 tv-yyyyMMdd 文件名`() {
        assertEquals("tv-20261111.txt", DiagMath.fileName("2026-11-11"))
    }

    @Test
    fun `已经是八位数字也认`() {
        assertEquals("tv-20260101.txt", DiagMath.fileName("20260101"))
    }

    @Test
    fun `乱写的日期退化成 unknown 而不是抛异常`() {
        assertEquals("tv-unknown.txt", DiagMath.fileName(""))
        assertEquals("tv-unknown.txt", DiagMath.fileName("昨天"))
    }

    @Test
    fun `文件名解析回日期`() {
        assertEquals(today, DiagMath.epochOfFile("tv-20261111.txt"))
        assertNull(DiagMath.epochOfFile("tv-unknown.txt"))
        assertNull(DiagMath.epochOfFile("state.json"))
        assertNull(DiagMath.epochOfFile("tv-2026-11-11.txt"))
        assertNull(DiagMath.epochOfFile("tv-20261311.txt")) // 13 月
    }

    // ---------------- 过期清理 ----------------

    @Test
    fun `保留今天加前六天`() {
        assertFalse(DiagMath.expired("tv-20261111.txt", today))
        assertFalse(DiagMath.expired("tv-20261105.txt", today)) // 第 6 天前，还在
        assertTrue(DiagMath.expired("tv-20261104.txt", today))  // 第 7 天前，删
        assertTrue(DiagMath.expired("tv-20261001.txt", today))
    }

    @Test
    fun `不是我们起的文件名一律不删`() {
        // logs 目录里可能有家长自己放的文件，删了是要挨骂的
        assertFalse(DiagMath.expired("家长的笔记.txt", today))
        assertFalse(DiagMath.expired("tv-unknown.txt", today))
        assertFalse(DiagMath.expired("tv.json", today))
    }

    @Test
    fun `保留天数可以调整`() {
        assertTrue(DiagMath.expired("tv-20261109.txt", today, keepDays = 2))
        assertFalse(DiagMath.expired("tv-20261110.txt", today, keepDays = 2))
    }

    // ---------------- 200 KB 滚动 ----------------

    @Test
    fun `没超上限时后缀追加、顺序不变`() {
        val out = DiagMath.append("a\nb\n", "c\n", maxBytes = 4096)
        assertEquals("a\nb\nc\n", out)
    }

    @Test
    fun `超上限时整行丢最旧的并留一句提示`() {
        val existing = (1..50).joinToString("\n") { "line-$it" } + "\n"
        val out = DiagMath.append(existing, "newest\n", maxBytes = 200)
        assertTrue("实际 ${out.length} 字符", out.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(out.startsWith(DiagMath.ROLL_MARKER))
        assertTrue(out.contains("newest"))
        assertFalse("最旧的应该被丢掉", out.contains("line-1\n"))
        // 最新的一条一定在，否则滚动等于把证据丢了
        assertTrue(out.contains("line-50"))
    }

    @Test
    fun `上限按 UTF-8 字节算而不是字符数`() {
        // 中文 3 字节/字：60 个字 = 180 字节，按字符算会误判成没超
        val long = "错".repeat(60) + "\n"
        val out = DiagMath.append(long, "新\n", maxBytes = 120)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 120)
        assertTrue(out.contains("新"))
    }

    @Test
    fun `追加空内容不动原文件`() {
        assertEquals("a\n", DiagMath.append("a\n", "", maxBytes = 1024))
        assertEquals("a\n", DiagMath.append("a\n", "   \n", maxBytes = 1024))
    }

    @Test
    fun `连续滚动不会越滚越大`() {
        var text = ""
        repeat(200) { i ->
            text = DiagMath.append(text, "第 $i 条：${"x".repeat(80)}\n", maxBytes = 2048)
        }
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= 2048)
        assertTrue(text.contains("第 199 条"))
    }

    // ---------------- 格式化 ----------------

    @Test
    fun `一条异常包含时间 级别 标签 文案 与栈帧`() {
        val e = IllegalStateException("boom")
        val text = DiagMath.format(0L, "W", "tick", "守卫心跳异常", e)
        assertTrue(text.contains("W/tick"))
        assertTrue(text.contains("守卫心跳异常"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("at "))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `栈帧过多时只留前几条并说明还有多少`() {
        val e = RuntimeException("deep")
        e.stackTrace = Array(20) { StackTraceElement("com.family.F", "m$it", "F.kt", it + 1) }
        val text = DiagMath.format(0L, "E", "t", "", e, maxStack = 3)
        assertTrue(text.contains("m0"))
        assertTrue(text.contains("m2"))
        assertFalse(text.contains("com.family.F.m3("))
        assertTrue(text.contains("另有 17 帧"))
    }

    @Test
    fun `没有 message 的异常只写类名`() {
        val text = DiagMath.format(0L, "E", "t", "崩了", IllegalStateException())
        assertTrue(text.contains("java.lang.IllegalStateException\n"))
    }

    @Test
    fun `单行内容去换行并截断`() {
        // 换行本身写成字面 \n，这样一条异常永远只占一行
        assertEquals("a\\nb \\nc", DiagMath.oneLine("a\nb\r\nc", max = 100))
        assertTrue(DiagMath.oneLine("x".repeat(50), max = 10).length <= 11)
    }

    @Test
    fun `空文案不留下多余空格`() {
        val text = DiagMath.format(0L, "I", "tag", "  ")
        assertTrue(text.startsWith("1970-01-01"))
        assertFalse(text.contains("tag  \n"))
    }

    // ---------------- 去重 ----------------

    @Test
    fun `同一个坑的指纹一样 换标签就不一样`() {
        val e = IllegalStateException("boom")
        e.stackTrace = arrayOf(StackTraceElement("com.family.KidApp", "run", "KidApp.kt", 73))
        assertEquals(DiagMath.signature("tick", e), DiagMath.signature("tick", e))
        assertFalse(DiagMath.signature("tick", e) == DiagMath.signature("kiosk", e))
        assertEquals("tick", DiagMath.signature("tick", null))
    }

    @Test
    fun `一分钟内重复只放行一条并计数`() {
        val d = DiagMath.Deduper(quietMs = 60_000)
        assertTrue(d.accept("sig-a", 1_000))
        assertFalse(d.accept("sig-a", 2_000))
        assertFalse(d.accept("sig-a", 3_000))
        assertEquals(2, d.takeSkipped())
        assertEquals(0, d.takeSkipped()) // 取过就清零
    }

    @Test
    fun `过了静默窗口再放行 换指纹立刻放行`() {
        val d = DiagMath.Deduper(quietMs = 1_000)
        assertTrue(d.accept("a", 0))
        assertTrue(d.accept("a", 1_500))      // 窗口过了
        assertTrue(d.accept("b", 1_600))      // 另一个坑
        assertEquals(0, d.takeSkipped())
    }

    @Test
    fun `合并提示只在真有重复时出现`() {
        assertEquals("", DiagMath.repeatNote(0))
        assertTrue(DiagMath.repeatNote(7).contains("7 次"))
    }

    @Test
    fun `数出一个文件里有几条记录`() {
        val text = DiagMath.format(0L, "W", "a", "一") +
            DiagMath.format(0L, "W", "b", "二") + "随便一行\n"
        assertEquals(2, DiagMath.countEntries(text))
        assertEquals(0, DiagMath.countEntries(""))
    }
}
