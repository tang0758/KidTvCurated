package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderMathTest {

    @Test
    fun `同一个 key 颜色稳定 不同条目颜色不同`() {
        val a = PlaceholderMath.hueOf("p:/cartoon/01.mp4")
        assertEquals(a, PlaceholderMath.hueOf("p:/cartoon/01.mp4"))
        assertNotEquals(a, PlaceholderMath.hueOf("p:/cartoon/02.mp4"))
        assertTrue(a in 0..359)
        assertTrue(PlaceholderMath.hueOf("") in 0..359)
    }

    @Test
    fun `短标题不折行`() {
        assertEquals(listOf("小猪佩奇"), PlaceholderMath.lines("小猪佩奇"))
    }

    @Test
    fun `长标题折行 且最多三行 末行省略号`() {
        val rows = PlaceholderMath.lines("一二三四五六七八九十甲乙丙丁戊己庚辛壬癸子丑寅卯", perLine = 5)
        assertEquals(3, rows.size)
        assertTrue(rows.last().endsWith("…"))
        assertTrue(rows.all { it.length <= 5 })
    }

    @Test
    fun `空标题也有字可画`() {
        assertEquals(listOf("小剧场"), PlaceholderMath.lines("   "))
    }
}
