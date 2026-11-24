package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RotationMathTest {

    @Test
    fun `首次启用从第0天开始，同日重复打开不推进`() {
        val s0 = RotationMath.advance(null, "RAZ/AA", "2026-11-24")
        assertEquals(0, s0.dayIndex)
        assertEquals("2026-11-24", s0.lastDay)

        val s0Again = RotationMath.advance(s0, "RAZ/AA", "2026-11-24")
        assertEquals(0, s0Again.dayIndex)
    }

    @Test
    fun `关机多天后再开机只前进1天，不跳过未看集数`() {
        val day1 = RotationMath.State(dayIndex = 2, lastDay = "2026-11-24", folder = "RAZ/AA")
        // 中间关机 5 天，11-29 才开机
        val next = RotationMath.advance(day1, "RAZ/AA", "2026-11-29")
        assertEquals(3, next.dayIndex)
        assertEquals("2026-11-29", next.lastDay)
    }

    @Test
    fun `更换轮换目录自动从第0天重新开始`() {
        val prev = RotationMath.State(dayIndex = 5, lastDay = "2026-11-24", folder = "RAZ/AA")
        val switched = RotationMath.advance(prev, "RAZ/A", "2026-11-25")
        assertEquals(0, switched.dayIndex)
        assertEquals("RAZ/A", switched.folder)
    }

    @Test
    fun `系统日期倒退时不回退进度`() {
        val prev = RotationMath.State(dayIndex = 4, lastDay = "2026-11-24", folder = "RAZ/AA")
        val rolled = RotationMath.advance(prev, "RAZ/AA", "2026-11-20")
        assertEquals(4, rolled.dayIndex)
        assertEquals("2026-11-24", rolled.lastDay)
    }
}
