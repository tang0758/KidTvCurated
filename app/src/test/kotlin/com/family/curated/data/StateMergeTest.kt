package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPT-09 记录备份的纯逻辑测试。
 * 重点不是「能跑」，而是几条会真的伤到孩子的边界：
 * 合并不能把记录改小、坏备份不能污染本地、重装后用量必须补回来。
 */
class StateMergeTest {

    private fun snap(stamps: Set<String> = emptySet(), longs: Map<String, Long> = emptyMap()) =
        StateMerge.Snapshot(stamps, longs)

    /** 生成连续日期，用来测上限截断 */
    private fun daySeq(count: Int): List<String> {
        val out = ArrayList<String>(count)
        var y = 2024
        var m = 1
        var d = 1
        repeat(count) {
            out.add(String.format("%04d-%02d-%02d", y, m, d))
            d++
            val max = when (m) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                else -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
            }
            if (d > max) {
                d = 1
                m++
                if (m > 12) { m = 1; y++ }
            }
        }
        return out
    }

    @Test
    fun `只认白名单前缀`() {
        assertTrue(StateMerge.isLongKey("watch_2026-11-11"))
        assertTrue(StateMerge.isLongKey("ext_2026-11-11"))
        assertTrue(StateMerge.isLongKey("vids_2026-11-11"))
        assertTrue(StateMerge.isLongKey("pos_p:/cartoon/01.mp4"))
        assertTrue(StateMerge.isLongKey("dur_p:/cartoon/01.mp4"))
        assertTrue(StateMerge.isLongKey("front_p:/cartoon/01.mp4"))
        assertTrue(StateMerge.isLongKey("played_p:/cartoon/01.mp4"))
        // 设置类的东西不进备份文件：那是 tv.json 的职责，混在一起迟早打架
        assertFalse(StateMerge.isLongKey("schedule"))
        assertFalse(StateMerge.isLongKey("pin"))
        assertFalse(StateMerge.isLongKey("stamps"))
        assertFalse(StateMerge.isLongKey("nas_pass"))
        assertFalse(StateMerge.isLongKey("unsynced_json"))
    }

    @Test
    fun `用量与记录分开`() {
        assertTrue(StateMerge.isUsageKey("watch_2026-11-11"))
        assertTrue(StateMerge.isUsageKey("ext_2026-11-11"))
        assertFalse(StateMerge.isUsageKey("pos_p:/a.mp4"))
        assertTrue(StateMerge.isRecordKey("pos_p:/a.mp4"))
        assertTrue(StateMerge.isRecordKey("played_p:/a.mp4"))
        assertTrue(StateMerge.isRecordKey("stamps"))
        assertFalse(StateMerge.isRecordKey("watch_2026-11-11"))
    }

    @Test
    fun `日期键校验`() {
        assertTrue(StateMerge.isDayKey("2026-11-11"))
        assertTrue(StateMerge.isDayKey("2000-01-01"))
        assertFalse(StateMerge.isDayKey("2026-13-01"))
        assertFalse(StateMerge.isDayKey("2026-00-10"))
        assertFalse(StateMerge.isDayKey("2026-11-32"))
        assertFalse(StateMerge.isDayKey("20261111"))
        assertFalse(StateMerge.isDayKey("2026/11/11"))
        assertFalse(StateMerge.isDayKey(""))
        assertFalse(StateMerge.isDayKey("9999-11-11"))
    }

    @Test
    fun `打卡去重排序并丢掉非法日期`() {
        val s = StateMerge.sanitizeStamps(
            listOf("2026-11-02", "2026-11-01", "2026-11-02", " 2026-11-03 ", "2026-13-01", "乱写", "")
        )
        assertEquals(listOf("2026-11-01", "2026-11-02", "2026-11-03"), s.toList())
    }

    @Test
    fun `打卡超过上限只留最近的`() {
        val all = daySeq(500)
        val s = StateMerge.sanitizeStamps(all)
        assertEquals(StateMerge.MAX_STAMPS, s.size)
        // 截断要留最近的：越靠后的日期越新
        assertTrue(s.contains(all.last()))
        assertFalse(s.contains(all.first()))
    }

    @Test
    fun `长整数过滤负数未知前缀与超长键`() {
        val raw = mapOf(
            "pos_p:/a.mp4" to 1000L,
            "watch_2026-11-11" to 60_000L,
            "pos_bad_negative" to -5L,
            "pin" to 1234L,
            ("pos_" + "x".repeat(300)) to 10L
        )
        val m = StateMerge.sanitizeLongs(raw)
        assertEquals(2, m.size)
        assertEquals(1000L, m["pos_p:/a.mp4"])
        assertEquals(60_000L, m["watch_2026-11-11"])
        assertFalse(m.containsKey("pos_bad_negative"))
        assertFalse(m.containsKey("pin"))
    }

    @Test
    fun `合并只升不降`() {
        val local = snap(
            longs = mapOf("watch_2026-11-11" to 600_000L, "pos_p:/a.mp4" to 120_000L)
        )
        val remote = snap(
            longs = mapOf("watch_2026-11-11" to 60_000L, "pos_p:/a.mp4" to 300_000L)
        )
        val m = StateMerge.merge(local, remote)
        // 本地用量更大时不能被备份改小（否则重装成了「少扣时间」）
        assertEquals(600_000L, m.longs["watch_2026-11-11"])
        // 备份里的进度更远时取更远的
        assertEquals(300_000L, m.longs["pos_p:/a.mp4"])
    }

    @Test
    fun `打卡取并集`() {
        val m = StateMerge.merge(
            snap(setOf("2026-11-01", "2026-11-02")),
            snap(setOf("2026-11-02", "2026-11-03"))
        )
        assertEquals(3, m.stamps.size)
        assertTrue(m.stamps.containsAll(listOf("2026-11-01", "2026-11-02", "2026-11-03")))
    }

    @Test
    fun `合并幂等`() {
        val local = snap(setOf("2026-11-01"), mapOf("pos_p:/a.mp4" to 100L, "watch_2026-11-11" to 5L))
        val remote = snap(setOf("2026-11-02"), mapOf("pos_p:/a.mp4" to 900L))
        val once = StateMerge.merge(local, remote)
        val twice = StateMerge.merge(once, remote)
        assertEquals(once.stamps, twice.stamps)
        assertEquals(once.longs, twice.longs)
    }

    @Test
    fun `raised 只数真正变多的`() {
        val local = snap(setOf("2026-11-01"), mapOf("pos_p:/a.mp4" to 100L, "pos_p:/b.mp4" to 500L))
        val remote = snap(
            setOf("2026-11-01", "2026-11-02"),
            mapOf("pos_p:/a.mp4" to 900L, "pos_p:/b.mp4" to 200L)
        )
        val m = StateMerge.merge(local, remote)
        // 打卡多 1 天 + a.mp4 进度变大 1 项；b.mp4 本地更远，不算
        assertEquals(2, StateMerge.raised(local, m))
    }

    @Test
    fun `已完成合并时 raised 为 0`() {
        val local = snap(setOf("2026-11-01"), mapOf("pos_p:/a.mp4" to 900L))
        val m = StateMerge.merge(local, snap(setOf("2026-11-01"), mapOf("pos_p:/a.mp4" to 900L)))
        assertEquals(0, StateMerge.raised(local, m))
    }

    @Test
    fun `onlyUsage 只留时长账本`() {
        val u = StateMerge.onlyUsage(
            snap(
                setOf("2026-11-01", "2026-11-02"),
                mapOf(
                    "watch_2026-11-11" to 600_000L,
                    "ext_2026-11-11" to 100_000L,
                    "vids_2026-11-11" to 3L,
                    "pos_p:/a.mp4" to 120_000L
                )
            )
        )
        assertTrue(u.stamps.isEmpty())
        assertEquals(3, u.longs.size)
        assertTrue(u.longs.containsKey("watch_2026-11-11"))
        assertFalse(u.longs.containsKey("pos_p:/a.mp4"))
    }

    @Test
    fun `onlyRecords 只留打卡与进度`() {
        val r = StateMerge.onlyRecords(
            snap(
                setOf("2026-11-01"),
                mapOf("watch_2026-11-11" to 600_000L, "pos_p:/a.mp4" to 120_000L)
            )
        )
        assertEquals(1, r.stamps.size)
        assertEquals(1, r.longs.size)
        assertTrue(r.longs.containsKey("pos_p:/a.mp4"))
    }

    @Test
    fun `什么时候该问家长`() {
        val remote = snap(setOf("2026-11-01"), mapOf("pos_p:/a.mp4" to 1L))
        // 本地一条打卡都没有 + 远端有东西 → 问
        assertTrue(StateMerge.shouldPrompt(0, remote))
        // 本地本来就有记录 → 不问，问了只会烦人
        assertFalse(StateMerge.shouldPrompt(3, remote))
        // 远端是空的 / 读不到 → 不问
        assertFalse(StateMerge.shouldPrompt(0, null))
        assertFalse(StateMerge.shouldPrompt(0, snap()))
    }

    @Test
    fun `记录原值为 0 才去 NAS 补用量`() {
        // 正常冷启动：今天已经有记录 → 不读 NAS（省一次 SMB 往返）
        assertFalse(StateMerge.shouldMergeUsage(60_000L))
        // 重装 / pm clear → 记录是 0，必须去补
        assertTrue(StateMerge.shouldMergeUsage(0L))
        // TC-46 的现场：只清 kid_extras 里的 watch_，daymax 还顶着 18522。
        // 传进来的必须是记录原值 0，不能是 max(watch_, daymax_) = 18522，
        // 否则这里会返回 false，补回逻辑被永久关在门外
        assertTrue(StateMerge.shouldMergeUsage(0L))
        // 脏数据（负数）也当「没有记录」处理，宁可多读一次
        assertTrue(StateMerge.shouldMergeUsage(-1L))
    }

    @Test
    fun `摘要文案能看懂`() {
        assertEquals("空记录", StateMerge.summary(snap()))
        assertEquals("3 天打卡", StateMerge.summary(snap(setOf("2026-11-01", "2026-11-02", "2026-11-03"))))
        assertEquals(
            "2 天打卡 · 1 条播放进度",
            StateMerge.summary(
                snap(
                    setOf("2026-11-01", "2026-11-02"),
                    mapOf("pos_p:/a.mp4" to 5000L, "pos_p:/b.mp4" to 0L)
                )
            )
        )
    }
}
