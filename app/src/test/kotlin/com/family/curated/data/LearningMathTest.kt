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
        // w1 应该出现 2 次，w2 出现 1 次，且 w1 不应相邻连续出现
        assertEquals(3, filtered.size)
        assertEquals(2, filtered.count { it.id == "w1" })
        assertEquals(1, filtered.count { it.id == "w2" })
        assertEquals(listOf("w1", "w2", "w1"), filtered.map { it.id })
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

    @Test
    fun `方案B - 自定义古诗按类型替换内置古诗，其它内置类型保持不变`() {
        val myPoem1 = LearningCard(CardType.POEM, "自配静夜思", sub = "唐·李白", lines = listOf("床前明月光"))
        val myPoem2 = LearningCard(CardType.POEM, "自配春晓", sub = "唐·孟浩然", lines = listOf("春眠不觉晓"))
        val learning = Learning(useBuiltin = true, custom = listOf(myPoem1, myPoem2))

        val deck = learning.deck()
        val poems = deck.filter { it.type == CardType.POEM }
        val words = deck.filter { it.type == CardType.WORD }
        val arts = deck.filter { it.type == CardType.ART }
        val texts = deck.filter { it.type == CardType.TEXT }

        assertEquals(listOf("自配静夜思", "自配春晓"), poems.map { it.title })
        assertEquals(LearningLibrary.words.size, words.size)
        assertEquals(LearningLibrary.arts.size, arts.size)
        assertEquals(LearningLibrary.texts.size, texts.size)
    }

    @Test
    fun `未配置自定义卡片时保留全部内置类型`() {
        val deck = Learning(useBuiltin = true, custom = emptyList()).deck()
        assertEquals(LearningLibrary.poems.size, deck.count { it.type == CardType.POEM })
        assertEquals(LearningLibrary.words.size, deck.count { it.type == CardType.WORD })
        assertEquals(LearningLibrary.arts.size, deck.count { it.type == CardType.ART })
        assertEquals(LearningLibrary.texts.size, deck.count { it.type == CardType.TEXT })
    }

    @Test
    fun `古诗按逗号与句末标点自动分行`() {
        val split = LearningMath.splitPoemLines(
            listOf(
                "两个黄鹂鸣翠柳，一行白鹭上青天。",
                "窗含西岭千秋雪，门泊东吴万里船。"
            )
        )
        assertEquals(
            listOf(
                "两个黄鹂鸣翠柳，",
                "一行白鹭上青天。",
                "窗含西岭千秋雪，",
                "门泊东吴万里船。"
            ),
            split
        )
    }

    @Test
    fun `锁屏学习角复用打卡词库替换内置单词，且自定义单词优先级更高`() {
        val onlineW = listOf(
            LearningCard(CardType.WORD, "giraffe", note = "长颈鹿"),
            LearningCard(CardType.WORD, "panda", note = "熊猫")
        )
        val deckReuse = Learning(useBuiltin = true).deck(onlineWords = onlineW)
        assertEquals(listOf("giraffe", "panda"), deckReuse.filter { it.type == CardType.WORD }.map { it.title })
        assertEquals(LearningLibrary.poems.size, deckReuse.count { it.type == CardType.POEM })

        val customW = listOf(LearningCard(CardType.WORD, "custom_cat", note = "自定义猫"))
        val deckCustom = Learning(useBuiltin = true, custom = customW).deck(onlineWords = onlineW)
        assertEquals(listOf("custom_cat"), deckCustom.filter { it.type == CardType.WORD }.map { it.title })
    }
}
