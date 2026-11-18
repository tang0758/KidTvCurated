package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordCheckInMathTest {

    private fun w(name: String, id: String = "word_$name") =
        LearningCard(type = CardType.WORD, title = name, id = id)

    @Test
    fun `nextMemory follows Ebbinghaus 1-2-4-7 and graduates at stage 5`() {
        val today = "2026-11-18"
        val r1 = WordCheckInMath.nextMemory(0, WordCheckInMath.Action.KNOW, today)
        assertEquals(1, r1.nextStage)
        assertEquals(20261119L, r1.nextDueDayNum)
        assertEquals(CardMastery.UNMARKED, r1.nextMastery)

        val r2 = WordCheckInMath.nextMemory(1, WordCheckInMath.Action.KNOW, today)
        assertEquals(2, r2.nextStage)
        assertEquals(20261120L, r2.nextDueDayNum)

        val r3 = WordCheckInMath.nextMemory(2, WordCheckInMath.Action.KNOW, today)
        assertEquals(3, r3.nextStage)
        assertEquals(20261122L, r3.nextDueDayNum)

        val r4 = WordCheckInMath.nextMemory(3, WordCheckInMath.Action.KNOW, today)
        assertEquals(4, r4.nextStage)
        assertEquals(20261125L, r4.nextDueDayNum)

        val r5 = WordCheckInMath.nextMemory(4, WordCheckInMath.Action.KNOW, today)
        assertEquals(5, r5.nextStage)
        assertEquals(CardMastery.MASTERED, r5.nextMastery)
    }

    @Test
    fun `nextMemory steps back 2 stages on UNKNOWN and marks LEARNING`() {
        val today = "2026-11-18"
        val rFrom4 = WordCheckInMath.nextMemory(4, WordCheckInMath.Action.UNKNOWN, today)
        assertEquals(2, rFrom4.nextStage)
        assertEquals(20261119L, rFrom4.nextDueDayNum)
        assertEquals(CardMastery.LEARNING, rFrom4.nextMastery)

        val rFrom1 = WordCheckInMath.nextMemory(1, WordCheckInMath.Action.UNKNOWN, today)
        assertEquals(0, rFrom1.nextStage)
        assertEquals(20261119L, rFrom1.nextDueDayNum)
        assertEquals(CardMastery.LEARNING, rFrom1.nextMastery)
    }

    @Test
    fun `nextMemory graduates immediately on GRADUATE action`() {
        val r = WordCheckInMath.nextMemory(1, WordCheckInMath.Action.GRADUATE, "2026-11-18")
        assertEquals(WordCheckInMath.GRADUATE_STAGE, r.nextStage)
        assertEquals(CardMastery.MASTERED, r.nextMastery)
    }

    @Test
    fun `pickDailyWords excludes graduated and reserves quota for new words`() {
        val today = "2026-11-18"
        val cards = (1..14).map { w("w$it") }
        val mem = mutableMapOf<String, WordCheckInMath.MemoryState>()
        // w1..w2: 毕业（不再显示）
        mem["word_w1"] = WordCheckInMath.MemoryState(stage = 5, dueDayNum = 20300101L, mastery = CardMastery.MASTERED)
        mem["word_w2"] = WordCheckInMath.MemoryState(stage = 5, dueDayNum = 20300101L, mastery = CardMastery.MASTERED)
        // w3..w10: 8 个全部是今天到期的复习词（其中 w3 是 💔 不认识）
        mem["word_w3"] = WordCheckInMath.MemoryState(stage = 0, dueDayNum = 20261118L, mastery = CardMastery.LEARNING)
        for (i in 4..10) {
            mem["word_w$i"] = WordCheckInMath.MemoryState(stage = 2, dueDayNum = 20261118L, mastery = CardMastery.UNMARKED)
        }
        // w11..w14: 4 个新词（不在 mem 中）

        val picked = WordCheckInMath.pickDailyWords(cards, mem, today, count = 10, seed = 42L)
        assertEquals(10, picked.size)
        val pickedIds = picked.map { it.id }.toSet()
        // 毕业词绝不出现在还有未学/复习词的队列中
        assertFalse("word_w1" in pickedIds)
        assertFalse("word_w2" in pickedIds)
        // 💔 不认识词必中
        assertTrue("word_w3" in pickedIds)
        // 至少保留 3 个名额给新词
        val newPickedCount = pickedIds.count { it in setOf("word_w11", "word_w12", "word_w13", "word_w14") }
        assertTrue(newPickedCount >= 3)
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
    fun `pickDailyWords cycles graduated words only when all words graduated`() {
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
        assertEquals(4, WordCheckInMath.streak(stamps, "2026-03-03"))
        assertEquals(0, WordCheckInMath.streak(stamps, "2026-03-04"))
    }

    @Test
    fun `splitZhNote splits word meaning and sentence translation`() {
        assertEquals("苹果" to "我吃一个苹果。", WordCheckInMath.splitZhNote("苹果 · 我吃一个苹果。"))
        assertEquals("香蕉" to "", WordCheckInMath.splitZhNote("香蕉"))
        assertEquals("" to "", WordCheckInMath.splitZhNote(null))
    }
}
