package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyReportMathTest {

    @Test
    fun `汇总四类掌握度、薄弱项排序与近7天新掌握`() {
        val now = 1_795_500_000_000L
        val twoDaysAgo = now - 2L * 24L * 3_600_000L
        val tenDaysAgo = now - 10L * 24L * 3_600_000L
        val todayKey = "2026-11-24"
        val todayNum = WordCheckInMath.dayToNum(todayKey)

        val w1 = LearningCard(CardType.WORD, "apple", id = "word_apple")
        val w2 = LearningCard(CardType.WORD, "arm", id = "word_arm")
        val w3 = LearningCard(CardType.WORD, "cat", id = "word_cat")
        val h1 = LearningCard(CardType.TEXT, "哭", id = "text_ku")
        val h2 = LearningCard(CardType.TEXT, "笑", id = "text_xiao")
        val p1 = LearningCard(CardType.POEM, "绝句", id = "poem_jueju")
        val a1 = LearningCard(CardType.ART, "星夜", id = "art_star")

        val memories = mapOf(
            "word_apple" to WordCheckInMath.MemoryState(stage = 2, dueDayNum = todayNum + 3, mastery = CardMastery.MASTERED),
            "word_arm" to WordCheckInMath.MemoryState(stage = 0, dueDayNum = todayNum, mastery = CardMastery.LEARNING),
            "word_cat" to WordCheckInMath.MemoryState(stage = 1, dueDayNum = todayNum - 1, mastery = CardMastery.UNMARKED),
            "text_ku" to WordCheckInMath.MemoryState(stage = 5, dueDayNum = 0, mastery = CardMastery.MASTERED),
            "poem_jueju" to WordCheckInMath.MemoryState(stage = 0, dueDayNum = 0, mastery = CardMastery.MASTERED)
        )
        val timestamps = mapOf(
            "word_apple" to twoDaysAgo,
            "text_ku" to now - 3_600_000L,
            "poem_jueju" to tenDaysAgo // 超过 7 天，不应出现在近 7 天动态
        )

        val report = StudyReportMath.build(
            words = listOf(w1, w2, w3),
            hanzi = listOf(h1, h2),
            poems = listOf(p1),
            arts = listOf(a1),
            memories = memories,
            timestamps = timestamps,
            todayDayKey = todayKey,
            nowMs = now
        )

        assertEquals(7, report.totalCards)
        assertEquals(3, report.totalMastered)
        assertEquals(1, report.totalLearning)

        val wordSum = report.byType.first { it.type == CardType.WORD }
        assertEquals(3, wordSum.total)
        assertEquals(1, wordSum.mastered)
        assertEquals(1, wordSum.learning)
        assertEquals(1, wordSum.unmarked)

        // 薄弱项：word_arm（明确重点再学）排在 word_cat（到期复习）前面
        assertEquals(listOf("word_arm", "word_cat"), report.weakItems.map { it.card.id })
        assertTrue(report.weakItems[0].isMarkedLearning)

        // 近 7 天新掌握：text_ku 最新排第一，word_apple 排第二，poem_jueju 超过 7 天被过滤
        assertEquals(listOf("text_ku", "word_apple"), report.recentMastered.map { it.card.id })
    }
}
