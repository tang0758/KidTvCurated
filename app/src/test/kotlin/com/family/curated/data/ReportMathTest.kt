package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周报纯逻辑（OPT-08）。重点覆盖三件容易错的事：
 *  1. 周一是哪一天（周一 = 0，不是周日）；
 *  2. 第三方应用那段不能超过总时长（否则柱子画出负数段）；
 *  3. 平均只按「有记录的天」算，周一打开不会显示「平均 5 分钟」。
 */
class ReportMathTest {

    // 2026-11-11 是周三
    private val today = "2026-11-11"

    @Test
    fun `epoch day 往返 覆盖闰年与跨年`() {
        assertEquals(0L, ReportMath.toEpochDay(1970, 1, 1))
        assertEquals("1970-01-01", ReportMath.formatDay(0L))
        // 2024 是闰年：2/28 + 2 天 = 3/1
        val e = ReportMath.toEpochDay(2024, 2, 28)
        assertEquals("2024-03-01", ReportMath.formatDay(e + 2))
        // 跨年
        val last = ReportMath.toEpochDay(2026, 12, 31)
        assertEquals("2027-01-01", ReportMath.formatDay(last + 1))
        assertEquals("2026-01-01", ReportMath.formatDay(ReportMath.toEpochDay(2026, 1, 1)))
    }

    @Test
    fun `非法日期返回 null 而不是抛异常`() {
        assertNull(ReportMath.parseDay(null))
        assertNull(ReportMath.parseDay(""))
        assertNull(ReportMath.parseDay("2026/11/11"))
        assertNull(ReportMath.parseDay("2026-13-01"))
        assertNull(ReportMath.parseDay("abcd-ef-gh"))
        assertEquals(ReportMath.toEpochDay(2026, 11, 11), ReportMath.parseDay(today))
    }

    @Test
    fun `周一等于 0 周日等于 6`() {
        // 2026-11-09 是周一，11-15 是周日
        assertEquals(0, ReportMath.weekdayIndex(ReportMath.parseDay("2026-11-09")!!))
        assertEquals(6, ReportMath.weekdayIndex(ReportMath.parseDay("2026-11-15")!!))
        assertEquals("三", ReportMath.weekdayText(ReportMath.parseDay(today)!!))
    }

    @Test
    fun `本周七天从周一到周日 且包含今天`() {
        val dates = ReportMath.weekDates(today)
        assertEquals(7, dates.size)
        assertEquals("2026-11-09", dates.first())
        assertEquals("2026-11-15", dates.last())
        assertTrue(today in dates)
        assertEquals("11/09 - 11/15", ReportMath.weekRangeText(today))
    }

    @Test
    fun `周日属于本周 而不是下一周`() {
        val sunday = "2026-11-15"
        val dates = ReportMath.weekDates(sunday)
        assertEquals("2026-11-09", dates.first())
        assertTrue(sunday in dates)
    }

    @Test
    fun `拼一周时 ext 被夹到不超过总时长`() {
        val watch = mapOf("2026-11-10" to 30 * 60_000L)
        val ext = mapOf("2026-11-10" to 45 * 60_000L)  // 手改坏的 ext > total
        val rows = ReportMath.buildWeek(
            today,
            { watch[it] ?: 0L },
            { ext[it] ?: 0L },
            { it == "2026-11-09" }
        )
        val row = rows.first { it.date == "2026-11-10" }
        assertEquals(30 * 60_000L, row.totalMs)
        assertEquals(30 * 60_000L, row.extMs)
        assertEquals(0L, row.ownMs)
        assertTrue(rows.first { it.date == "2026-11-09" }.done)
        assertFalse(row.done)
        assertTrue(rows.first { it.date == today }.isToday)
    }

    @Test
    fun `今天的平均值只按有记录的天算`() {
        val watch = mapOf(
            "2026-11-09" to 20 * 60_000L,
            "2026-11-10" to 40 * 60_000L
        )
        val rows = ReportMath.buildWeek(today, { watch[it] ?: 0L }, { 0L }, { false })
        assertEquals(60 * 60_000L, ReportMath.totalMs(rows))
        assertEquals(2, ReportMath.activeDays(rows))
        // 60 分钟 / 2 天，而不是 / 7 天
        assertEquals(30 * 60_000L, ReportMath.averageMs(rows))
    }

    @Test
    fun `完全没有记录时算空`() {
        val rows = ReportMath.buildWeek(today, { 0L }, { 0L }, { false })
        assertTrue(ReportMath.isEmpty(rows))
        assertEquals(0L, ReportMath.averageMs(rows))
        // 全空时纵轴仍给一格，图表不会除零
        assertEquals(15 * 60_000L, ReportMath.scaleMax(rows, 0L))
    }

    @Test
    fun `纵轴至少覆盖每日上限 并向上取整到 15 分钟`() {
        val watch = mapOf("2026-11-10" to 37 * 60_000L)
        val rows = ReportMath.buildWeek(today, { watch[it] ?: 0L }, { 0L }, { false })
        assertEquals(45 * 60_000L, ReportMath.scaleMax(rows, 0L))
        // 上限比峰值大时按上限走，这样「用满了没有」看得出
        assertEquals(60 * 60_000L, ReportMath.scaleMax(rows, 60 * 60_000L))
    }

    @Test
    fun `柱子高度占比夹在 0 到 1`() {
        assertEquals(0f, ReportMath.ratio(0L, 1000L))
        assertEquals(0.5f, ReportMath.ratio(500L, 1000L))
        // 数据比刻度大（比如中途调低了上限）也不能溢出画到标题上
        assertEquals(1f, ReportMath.ratio(5000L, 1000L))
        assertEquals(0f, ReportMath.ratio(100L, 0L))
    }

    @Test
    fun `反复看只认两天及以上 并按天数倒序`() {
        val plays = mapOf(
            "p:/a/01.mp4" to 3,
            "p:/a/02.mp4" to 2,
            "p:/a/03.mp4" to 1
        )
        val out = ReportMath.topRepeated(plays)
        assertEquals(2, out.size)
        assertEquals("p:/a/01.mp4", out[0].key)
        assertEquals(3, out[0].value)
        assertEquals("01", out[0].title)
        // 只点开过一次的不算「反复」
        assertTrue(out.none { it.key == "p:/a/03.mp4" })
    }

    @Test
    fun `反复看最多三条 没有记录时为空`() {
        val many = (1..9).associate { "p:/a/$it.mp4" to 2 }
        assertEquals(3, ReportMath.topRepeated(many).size)
        assertTrue(ReportMath.topRepeated(emptyMap()).isEmpty())
    }

    @Test
    fun `看到一半只认 5 到 60 百分比 并按最近点开排序`() {
        val all = listOf(
            ReportMath.Progress("p:/a/early.mp4", 3 * 60_000, 60 * 60_000, 1000L),   // 5% 边界内
            ReportMath.Progress("p:/a/half.mp4", 20 * 60_000, 60 * 60_000, 3000L),   // 33%
            ReportMath.Progress("p:/a/late.mp4", 50 * 60_000, 60 * 60_000, 2000L),   // 83% 不算
            ReportMath.Progress("p:/a/tiny.mp4", 1_000, 60 * 60_000, 4000L),         // 0% 算误点
            ReportMath.Progress("p:/a/nodur.mp4", 60_000, 0L, 5000L)                 // 时长未知
        )
        val out = ReportMath.halfWatched(all)
        assertEquals(2, out.size)
        // 最近的排前面
        assertEquals("p:/a/half.mp4", out[0].key)
        assertEquals(33, out[0].value)
        assertEquals("p:/a/early.mp4", out[1].key)
    }

    @Test
    fun `没有权限数据时 看到一半为空`() {
        assertTrue(ReportMath.halfWatched(emptyList()).isEmpty())
    }

    @Test
    fun `路径派生标题 去掉前缀目录与扩展名`() {
        assertEquals("01", ReportMath.pathTitle("p:/cartoon/小猪佩奇/01.mp4"))
        assertEquals("小猪佩奇", ReportMath.pathTitle("p:/cartoon/小猪佩奇/"))
        assertEquals("小猪佩奇", ReportMath.pathTitle("p:/cartoon/小猪佩奇"))
        assertEquals("com.youku.phone", ReportMath.pathTitle("p:com.youku.phone"))
        assertEquals("(未命名)", ReportMath.pathTitle(""))
        assertEquals("(未命名)", ReportMath.pathTitle("p:"))
    }

    @Test
    fun `时长文案 超过一小时按小时加分钟`() {
        assertEquals("0 分钟", ReportMath.durationText(0L))
        assertEquals("25 分钟", ReportMath.durationText(25 * 60_000L))
        assertEquals("1 小时", ReportMath.durationText(60 * 60_000L))
        assertEquals("1 小时 30 分", ReportMath.durationText(90 * 60_000L))
    }
}
