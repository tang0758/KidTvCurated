package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LearningMathTest {

    @Test
    fun `卡片未设 ID 时自动按类别和标题派生稳定 ID`() {
        val card1 = LearningCard(CardType.POEM, "静夜思")
        assertEquals("poem_静夜思", LearningMath.cardId(card1))

        val card2 = LearningCard(CardType.WORD, "Apple Pie")
        assertEquals("word_apple_pie", LearningMath.cardId(card2))

        val cardWithId = LearningCard(CardType.POEM, "静夜思", id = "custom_id_01")
        assertEquals("custom_id_01", LearningMath.cardId(cardWithId))
    }

    @Test
    fun `加权降频 - 重点再学权重翻倍，普通权重为1`() {
        val c1 = LearningCard(CardType.WORD, "apple", id = "w1")
        val c2 = LearningCard(CardType.WORD, "banana", id = "w2")

        val masteries = mapOf(
            "w1" to CardMastery.LEARNING, // 重点
            "w2" to CardMastery.UNMARKED  // 普通
        )

        // 固定 Random
        val filtered = LearningMath.weightedFilter(listOf(c1, c2), masteries, Random(42))
        // w1 应该出现 2 次，w2 出现 1 次
        assertEquals(3, filtered.size)
        assertEquals(2, filtered.count { it.id == "w1" })
        assertEquals(1, filtered.count { it.id == "w2" })
    }

    @Test
    fun `加权降频 - 已认识降频至约15pct抽检`() {
        val cards = (1..1000).map { i ->
            LearningCard(CardType.WORD, "word_$i", id = "id_$i")
        }
        val masteries = cards.associate { it.id to CardMastery.MASTERED }

        val filtered = LearningMath.weightedFilter(cards, masteries, Random(12345))
        // 1000 张卡片 15% 抽样，数量应在 100 ~ 200 之间
        assertTrue("抽样数量 ${filtered.size} 应在 100~200 之间", filtered.size in 100..200)
    }

    @Test
    fun `全已掌握且未抽中时保底不为空`() {
        val cards = listOf(
            LearningCard(CardType.POEM, "诗1", id = "p1"),
            LearningCard(CardType.POEM, "诗2", id = "p2")
        )
        val masteries = mapOf(
            "p1" to CardMastery.MASTERED,
            "p2" to CardMastery.MASTERED
        )
        // 构造一个每次 nextFloat 都返回 0.99 (大于 0.15) 的伪随机
        val neverSampleRandom = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextFloat(): Float = 0.99f
        }

        val filtered = LearningMath.weightedFilter(cards, masteries, neverSampleRandom)
        // 触发保底机制，返回原卡片列表
        assertEquals(2, filtered.size)
    }
}
