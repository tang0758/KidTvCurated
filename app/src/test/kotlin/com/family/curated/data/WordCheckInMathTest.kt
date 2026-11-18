package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordCheckInMathTest {

    private fun w(name: String, id: String = "word_$name") =
        LearningCard(type = CardType.WORD, title = name, id = id)

    @Test
    fun `pickDailyWords prioritizes LEARNING then UNMARKED then MASTERED`() {
        val cards = listOf(w("a"), w("b"), w("c"), w("d"), w("e"))
        val mastery = mapOf(
            "word_a" to CardMastery.MASTERED,
            "word_b" to CardMastery.LEARNING,
            "word_c" to CardMastery.UNMARKED,
            "word_d" to CardMastery.MASTERED,
            "word_e" to CardMastery.LEARNING
        )
        val picked = WordCheckInMath.pickDailyWords(cards, mastery, count = 4, seed = 42L)
        assertEquals(4, picked.size)
        // 前两个必为 LEARNING（b, e）
        assertEquals(setOf("b", "e"), picked.subList(0, 2).map { it.title }.toSet())
        // 第 3 个必为 UNMARKED（c）
        assertEquals("c", picked[2].title)
        // 第 4 个从 MASTERED 兜底补充（a 或 d）
        assertTrue(picked[3].title in setOf("a", "d"))
    }

    @Test
    fun `pickDailyWords deduplicates and ignores non-word cards`() {
        val cards = listOf(
            LearningCard(type = CardType.POEM, title = "静夜思", id = "poem_1"),
            w("apple"),
            w("apple"),
            w("banana")
        )
        val picked = WordCheckInMath.pickDailyWords(cards, emptyMap(), count = 10)
        assertEquals(listOf("apple", "banana"), picked.map { it.title })
    }

    @Test
    fun `pickDailyWords cycles MASTERED when all words mastered`() {
        val cards = (1..15).map { w("w$it") }
        val mastery = cards.associate { it.id to CardMastery.MASTERED }
        val picked = WordCheckInMath.pickDailyWords(cards, mastery, count = 10, seed = 7L)
        assertEquals(10, picked.size)
        assertEquals(10, picked.map { it.id }.distinct().size)
    }

    @Test
    fun `isDone requires every queued id answered and handles empty`() {
        assertTrue(WordCheckInMath.isDone(emptyList(), emptySet()))
        val q = listOf("word_a", "word_b")
        assertFalse(WordCheckInMath.isDone(q, setOf("word_a")))
        assertTrue(WordCheckInMath.isDone(q, setOf("word_a", "word_b")))
    }

    @Test
    fun `canUnlockDailyGate requires both video gate and word check-in`() {
        assertTrue(WordCheckInMath.canUnlockDailyGate(true, true, true, true))
        assertFalse(WordCheckInMath.canUnlockDailyGate(true, true, true, false))
        assertFalse(WordCheckInMath.canUnlockDailyGate(true, false, true, true))
        assertTrue(WordCheckInMath.canUnlockDailyGate(false, false, true, true))
        assertFalse(WordCheckInMath.canUnlockDailyGate(false, false, true, false))
    }

    @Test
    fun `streak counts consecutive days including month and year boundaries`() {
        val stamps = setOf("2026-02-27", "2026-02-28", "2026-03-01", "2026-03-02")
        assertEquals(4, WordCheckInMath.streak(stamps, "2026-03-02"))
        // 今天未打、昨天打了，连续天数仍从昨天算
        assertEquals(4, WordCheckInMath.streak(stamps, "2026-03-03"))
        // 断了一天归零
        assertEquals(0, WordCheckInMath.streak(stamps, "2026-03-04"))
    }
}
