package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OPT-05 的纯计算回归：说错「还剩多久」比不说更伤信任，尤其是跨零点时段。 */
class TimeNoticeMathTest {

    private fun left(
        usedMs: Long = 0L,
        limit: Int = 0,
        tempMs: Long = 0L,
        remoteMs: Long = 0L,
        enabled: Boolean = false,
        minute: Int = 12 * 60,
        windows: List<TimeWindow> = emptyList()
    ) = TimeNoticeMath.compute(usedMs, limit, tempMs, remoteMs, enabled, minute, windows)

    @Test
    fun `只按每日限额计时`() {
        val r = left(usedMs = 18 * 60_000L, limit = 30)
        assertEquals(TimeNoticeMath.Kind.LIMIT, r.kind)
        assertEquals(12 * 60_000L, r.ms)
        assertEquals("今天还剩 12 分钟", TimeNoticeMath.label(r))
    }

    @Test
    fun `限额用尽显示时间到`() {
        val r = left(usedMs = 30 * 60_000L, limit = 30)
        assertEquals(0L, r.ms)
        assertEquals("时间到啦", TimeNoticeMath.label(r))
    }

    @Test
    fun `时段比限额先到就报时段`() {
        val w = listOf(TimeWindow(17 * 60 + 30, 20 * 60 + 30))
        val r = left(limit = 200, enabled = true, minute = 19 * 60 + 45, windows = w)
        assertEquals(TimeNoticeMath.Kind.WINDOW, r.kind)
        assertEquals(45 * 60_000L, r.ms)
        assertEquals("还能看 45 分钟", TimeNoticeMath.label(r))
    }

    @Test
    fun `限额比时段先到就报限额`() {
        val w = listOf(TimeWindow(17 * 60 + 30, 20 * 60 + 30))
        val r = left(limit = 10, enabled = true, minute = 19 * 60 + 45, windows = w)
        assertEquals(TimeNoticeMath.Kind.LIMIT, r.kind)
        assertEquals(10 * 60_000L, r.ms)
    }

    @Test
    fun `跨零点时段的前半段算到次日`() {
        val w = listOf(TimeWindow(20 * 60, 60))   // 20:00 - 01:00
        val r = left(limit = 600, enabled = true, minute = 23 * 60 + 30, windows = w)
        assertEquals(90 * 60_000L, r.ms)
        assertEquals(TimeNoticeMath.Kind.WINDOW, r.kind)
    }

    @Test
    fun `跨零点时段的后半段不会算成负数`() {
        val w = listOf(TimeWindow(20 * 60, 60))
        val r = left(limit = 600, enabled = true, minute = 30, windows = w)
        assertEquals(30 * 60_000L, r.ms)
    }

    @Test
    fun `多段窗口取最近结束的那一段`() {
        val w = listOf(TimeWindow(9 * 60, 11 * 60 + 30), TimeWindow(16 * 60, 21 * 60))
        assertEquals(60L, TimeNoticeMath.minutesToWindowEnd(10 * 60 + 30, w))
        assertEquals(
            TimeNoticeMath.NONE_LEFT,
            TimeNoticeMath.minutesToWindowEnd(13 * 60, w)
        )
    }

    @Test
    fun `临时放行优先于限额与时段`() {
        val w = listOf(TimeWindow(17 * 60, 20 * 60))
        val r = left(
            usedMs = 90 * 60_000L, limit = 30, tempMs = 5 * 60_000L,
            enabled = true, minute = 18 * 60, windows = w
        )
        assertEquals(TimeNoticeMath.Kind.TEMP, r.kind)
        assertEquals(5 * 60_000L, r.ms)
    }

    @Test
    fun `远程锁定优先级最高`() {
        val r = left(limit = 30, tempMs = 60_000L, remoteMs = 60_000L)
        assertEquals(TimeNoticeMath.Kind.REMOTE, r.kind)
        assertEquals("家长已暂停观看", TimeNoticeMath.label(r))
    }

    @Test
    fun `没有任何限制时不显示`() {
        val r = left()
        assertEquals(TimeNoticeMath.Kind.NONE, r.kind)
        assertEquals(0L, r.ms)
        assertEquals("", TimeNoticeMath.label(r))
    }

    @Test
    fun `时段配置关掉时只看限额`() {
        val w = listOf(TimeWindow(17 * 60, 18 * 60))
        val r = left(limit = 30, enabled = false, minute = 23 * 60, windows = w)
        assertEquals(TimeNoticeMath.Kind.LIMIT, r.kind)
        assertEquals(30 * 60_000L, r.ms)
    }

    @Test
    fun `阈值只在跨过时触发一次`() {
        assertNull(TimeNoticeMath.thresholdTag(400_000L))
        assertNull(TimeNoticeMath.thresholdTag(0L))
        assertEquals("5min", TimeNoticeMath.thresholdTag(300_000L))
        assertEquals("5min", TimeNoticeMath.thresholdTag(61_000L))
        assertEquals("1min", TimeNoticeMath.thresholdTag(60_000L))
        assertEquals("1min", TimeNoticeMath.thresholdTag(1L))
    }

    @Test
    fun `文案向上取整且带小时`() {
        assertEquals("1 分钟", TimeNoticeMath.minutesText(1L))
        assertEquals("2 分钟", TimeNoticeMath.minutesText(60_001L))
        assertEquals("1 小时 5 分钟", TimeNoticeMath.minutesText(65 * 60_000L))
    }
}
