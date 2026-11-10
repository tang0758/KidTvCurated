package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClockMath 的纯逻辑单测（不碰 Android API，`gradlew test` 直接跑）。
 *
 * 覆盖的是最容易伤到孩子的两类错误：
 *  1. 该锁定的时候被改日期绕过去了；
 *  2. 正常使用（含 NTP 小步校正）被误判成作弊，冤枉孩子。
 */
class ClockMathTest {

    private val day = 24 * 3_600_000L
    private val t0 = 1_760_000_000_000L // 随便一个固定时刻，避免依赖真实时钟

    @Test
    fun `第一次运行直接采信墙上时间`() {
        val r = ClockMath.evaluate(t0, 1_000L, ClockMath.State())
        assertEquals(t0, r.guarded)
        assertEquals(t0, r.state.goodWall)
        assertEquals(1_000L, r.state.goodRt)
        assertFalse(r.anomaly)
        assertFalse(r.state.frozen)
    }

    @Test
    fun `正常走动时跟着墙上时间走`() {
        val s = ClockMath.State(t0, 1_000L, false)
        // 60 秒后，墙上时间和单调时间一致
        val r = ClockMath.evaluate(t0 + 60_000L, 61_000L, s)
        assertEquals(t0 + 60_000L, r.guarded)
        assertFalse(r.anomaly)
        assertFalse(r.state.frozen)
    }

    @Test
    fun `小幅偏差（NTP 校正）不触发冻结`() {
        val s = ClockMath.State(t0, 1_000L, false)
        // 墙上时间往回跳了 2 分钟
        val r = ClockMath.evaluate(t0 + 60_000L - 120_000L, 61_000L, s)
        assertEquals(t0 + 60_000L - 120_000L, r.guarded)
        assertFalse(r.anomaly)
        assertFalse(r.state.frozen)
    }

    @Test
    fun `把日期改到明天也不会让今天归零`() {
        val s = ClockMath.State(t0, 1_000L, false)
        // 孩子把电视日期改到明天同一时刻
        val r = ClockMath.evaluate(t0 + day, 61_000L, s)
        // 校正后的时间仍然停在「今天 + 60 秒」，日期键不会跳到明天
        assertEquals(t0 + 60_000L, r.guarded)
        assertTrue(r.anomaly)
        assertTrue(r.state.frozen)
        // 基线不动，后续每一次都继续按真实流逝推进
        val r2 = ClockMath.evaluate(t0 + day, 121_000L, r.state)
        assertEquals(t0 + 120_000L, r2.guarded)
        // 只记一次异常，别每次 tick 都刷一条
        assertFalse(r2.anomaly)
    }

    @Test
    fun `日期往回调也不会把用量退回去`() {
        val s = ClockMath.State(t0, 1_000L, false)
        // 墙上时间倒退一天
        val r = ClockMath.evaluate(t0 - day, 61_000L, s)
        assertEquals(t0 + 60_000L, r.guarded)
        assertTrue(r.state.frozen)
    }

    @Test
    fun `时间被改回来之后自动解除冻结`() {
        val s = ClockMath.State(t0, 1_000L, false)
        val frozen = ClockMath.evaluate(t0 + day, 61_000L, s)
        assertTrue(frozen.state.frozen)
        // 家长把日期改回正确时间（此时真实流逝 2 分钟）
        val back = ClockMath.evaluate(t0 + 120_000L, 121_000L, frozen.state)
        assertEquals(t0 + 120_000L, back.guarded)
        assertTrue(back.recovered)
        assertFalse(back.state.frozen)
        // 恢复后重新建立基线，后续正常
        val next = ClockMath.evaluate(t0 + 180_000L, 181_000L, back.state)
        assertEquals(t0 + 180_000L, next.guarded)
        assertFalse(next.anomaly)
    }
}
