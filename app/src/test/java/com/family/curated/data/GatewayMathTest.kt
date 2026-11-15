package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关缓存状态机。2026-11-15 的主线程探测事故就是这里没被钉住：
 * 未知状态必须走 SMB、探测结果必须有 TTL、降级后不能立刻又去探。
 */
class GatewayMathTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `从未探测过不算新鲜且必须走 SMB`() {
        val s = GatewayMath.State()
        assertFalse(GatewayMath.fresh(s, now))
        assertTrue(GatewayMath.shouldProbe(s, now))
        assertFalse(GatewayMath.httpPreferred(s, now))
    }

    @Test
    fun `探测成功才优先 HTTP`() {
        val s = GatewayMath.recorded(true, now)
        assertTrue(GatewayMath.fresh(s, now))
        assertFalse(GatewayMath.shouldProbe(s, now))
        assertTrue(GatewayMath.httpPreferred(s, now))
    }

    @Test
    fun `探测失败在 TTL 内不重探且走 SMB`() {
        val s = GatewayMath.recorded(false, now)
        assertTrue(GatewayMath.fresh(s, now))
        assertFalse(GatewayMath.shouldProbe(s, now))
        assertFalse(GatewayMath.httpPreferred(s, now))
    }

    @Test
    fun `恰好到 TTL 边界即过期`() {
        val s = GatewayMath.recorded(true, now)
        assertFalse(GatewayMath.fresh(s, now + GatewayMath.PROBE_TTL_MS))
        assertTrue(GatewayMath.shouldProbe(s, now + GatewayMath.PROBE_TTL_MS))
        assertFalse(GatewayMath.httpPreferred(s, now + GatewayMath.PROBE_TTL_MS))
    }

    @Test
    fun `TTL 之前最后一点仍新鲜`() {
        val s = GatewayMath.recorded(true, now)
        val almost = now + GatewayMath.PROBE_TTL_MS - 1
        assertTrue(GatewayMath.fresh(s, almost))
        assertFalse(GatewayMath.shouldProbe(s, almost))
        assertTrue(GatewayMath.httpPreferred(s, almost))
    }

    @Test
    fun `系统时间回拨判过期而不是把缓存永久钉住`() {
        val s = GatewayMath.recorded(true, now)
        assertFalse(GatewayMath.fresh(s, now - 1_000L))
        assertTrue(GatewayMath.shouldProbe(s, now - 1_000L))
        assertFalse(GatewayMath.httpPreferred(s, now - 1_000L))
    }

    @Test
    fun `播放出错降级后 TTL 内不再探 HTTP`() {
        val s = GatewayMath.invalidated(now)
        assertEquals(false, s.available)
        assertFalse(GatewayMath.httpPreferred(s, now + 10_000L))
        assertFalse(GatewayMath.shouldProbe(s, now + 10_000L))
        assertTrue(GatewayMath.shouldProbe(s, now + GatewayMath.PROBE_TTL_MS))
    }

    @Test
    fun `降级之后重新探测成功可以恢复 HTTP`() {
        val down = GatewayMath.invalidated(now)
        val later = now + GatewayMath.PROBE_TTL_MS
        assertTrue(GatewayMath.shouldProbe(down, later))
        val up = GatewayMath.recorded(true, later)
        assertTrue(GatewayMath.httpPreferred(up, later))
    }

    @Test
    fun `自定义 TTL 生效`() {
        val s = GatewayMath.recorded(true, now)
        assertTrue(GatewayMath.fresh(s, now + 99, 100L))
        assertFalse(GatewayMath.fresh(s, now + 100, 100L))
    }

    @Test
    fun `等待上限必须大于单次探测超时且远小于 ANR 阈值`() {
        assertTrue(GatewayMath.PROBE_WAIT_MS > 1_200L)
        assertTrue(GatewayMath.PROBE_WAIT_MS < 5_000L)
        assertTrue(GatewayMath.PROBE_TTL_MS > GatewayMath.PROBE_WAIT_MS)
    }

    @Test
    fun `recorded 覆盖旧状态而不是叠加`() {
        val first = GatewayMath.recorded(true, now)
        val second = GatewayMath.recorded(false, now + 1_000L)
        assertEquals(false, second.available)
        assertEquals(now + 1_000L, second.lastProbeMs)
        assertFalse(GatewayMath.httpPreferred(second, now + 1_000L))
        assertTrue(GatewayMath.httpPreferred(first, now))
    }
}
