package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-01 第 6.3 节校准规则的纯函数单测 */
class ExtClockMathTest {

    @Test
    fun `极短会话按零落账`() {
        assertEquals(0L, ExtClockMath.calibrate(0L, 0L))
        assertEquals(0L, ExtClockMath.calibrate(999L, 999L))
    }

    @Test
    fun `拿不到系统累计值时用估算`() {
        assertEquals(180_000L, ExtClockMath.calibrate(180_000L, 0L))
        assertEquals(180_000L, ExtClockMath.calibrate(180_000L, -5L))
    }

    @Test
    fun `偏差在百分之二十内以估算为准`() {
        // 180s vs 198s = 10%
        assertEquals(180_000L, ExtClockMath.calibrate(180_000L, 198_000L))
        // 正好 20% 仍以估算为准
        assertEquals(180_000L, ExtClockMath.calibrate(180_000L, 216_000L))
    }

    @Test
    fun `偏差超过百分之二十以系统值为准`() {
        // 180s vs 240s = 33%：典型场景是孩子中途切走了但探针没抓到事件
        assertEquals(240_000L, ExtClockMath.calibrate(180_000L, 240_000L))
        // 系统值明显偏小也照用（部分 ROM 退到后台才 flush）
        assertEquals(100_000L, ExtClockMath.calibrate(180_000L, 100_000L))
    }

    @Test
    fun `长时间会话不会整数溢出`() {
        val fourHours = 4 * 60 * 60_000L
        assertEquals(fourHours, ExtClockMath.calibrate(fourHours, fourHours))
    }
}
