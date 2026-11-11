package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败退避：真机上 NAS 不可达时曾每秒重连一次（见 [BackoffMath] 注释）。
 * 这几条把「多久以后再试」钉住，防止以后又退回每秒一次。
 */
class BackoffMathTest {

    @Test
    fun `上次成功时不退避`() {
        assertEquals(0L, BackoffMath.retryDelayMs(0))
    }

    @Test
    fun `失败次数为负也当成功处理`() {
        assertEquals(0L, BackoffMath.retryDelayMs(-3))
    }

    @Test
    fun `第一次失败等五分钟`() {
        assertEquals(5 * 60_000L, BackoffMath.retryDelayMs(1))
    }

    @Test
    fun `失败越多退得越远`() {
        assertEquals(10 * 60_000L, BackoffMath.retryDelayMs(2))
        assertEquals(15 * 60_000L, BackoffMath.retryDelayMs(3))
    }

    @Test
    fun `第六次失败正好到上限`() {
        assertEquals(BackoffMath.MAX_MS, BackoffMath.retryDelayMs(6))
    }

    @Test
    fun `超过上限就封顶而不是无限增长`() {
        assertEquals(BackoffMath.MAX_MS, BackoffMath.retryDelayMs(7))
        assertEquals(BackoffMath.MAX_MS, BackoffMath.retryDelayMs(100))
    }

    @Test
    fun `极大失败次数不会溢出成负数`() {
        val d = BackoffMath.retryDelayMs(Int.MAX_VALUE)
        assertEquals(BackoffMath.MAX_MS, d)
        assertTrue("退避间隔必须为正", d > 0)
    }

    @Test
    fun `退避随失败次数单调不减`() {
        var prev = 0L
        for (n in 0..40) {
            val d = BackoffMath.retryDelayMs(n)
            assertTrue("第 $n 次的退避 ${d}ms 比上一次 ${prev}ms 还短", d >= prev)
            prev = d
        }
    }

    @Test
    fun `退避基数与一轮定时备份周期一致`() {
        // StateBackup.INTERVAL_MS 也是 5 分钟；两边对齐，保证「失败退避」
        // 不会比「正常定时」更频繁。（不直接引用它：那个 object 依赖 Handler，纯 JVM 起不来）
        assertEquals(5 * 60_000L, BackoffMath.BASE_MS)
    }
}
