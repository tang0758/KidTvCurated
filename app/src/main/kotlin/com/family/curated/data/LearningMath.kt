package com.family.curated.data

import kotlin.random.Random

/**
 * 学习角卡片掌握度状态（OPT-28B）。
 * - LEARNING (-1): 重点再学，轮播权重翻倍 (2.0)
 * - UNMARKED (0): 默认未标记，正常轮播权重 (1.0)
 * - MASTERED (1): 已认识，低频抽检复习 (15% 概率)
 */
enum class CardMastery(val value: Int) {
    LEARNING(-1),
    UNMARKED(0),
    MASTERED(1);

    companion object {
        fun of(v: Int): CardMastery = when (v) {
            -1 -> LEARNING
            1 -> MASTERED
            else -> UNMARKED
        }
    }
}

/**
 * 学习角 2.0 纯计算逻辑（纯 JVM，零 Android 依赖，可在 JUnit 中单测）。
 */
object LearningMath {

    /**
     * 为卡片生成确定性稳定 ID。
     * 若卡片已有 id 则用之；否则由 "type_title" 派生，消除跨设备标签错乱。
     */
    fun cardId(card: LearningCard): String {
        if (card.id.isNotBlank()) return card.id.trim()
        val cleanTitle = card.title.trim().lowercase().replace("\\s+".toRegex(), "_")
        return "${card.type.key}_$cleanTitle"
    }

    /**
     * 加权降频抽样（加权轮播算法）：
     * - LEARNING (-1): 在轮播池中放入 2 份（高频强化）
     * - UNMARKED (0): 在轮播池中放入 1 份（标准权重）
     * - MASTERED (1): 每次以 15% 概率抽入复习池（艾宾浩斯防遗忘）
     *
     * 保底机制：若全部卡片均已掌握且本轮 15% 均未抽中，保底保留全部卡片，杜绝空列表崩溃。
     */
    fun weightedFilter(
        cards: List<LearningCard>,
        masteryMap: Map<String, CardMastery>,
        random: Random = Random.Default
    ): List<LearningCard> {
        if (cards.isEmpty()) return emptyList()

        val result = ArrayList<LearningCard>()

        for (c in cards) {
            val id = cardId(c)
            val m = masteryMap[id] ?: CardMastery.UNMARKED
            when (m) {
                CardMastery.LEARNING -> {
                    result.add(c)
                    result.add(c) // 权重 2.0
                }
                CardMastery.UNMARKED -> {
                    result.add(c) // 权重 1.0
                }
                CardMastery.MASTERED -> {
                    if (random.nextFloat() < 0.15f) {
                        result.add(c) // 15% 概率抽检
                    }
                }
            }
        }

        // 保底：若全部卡片都被过滤掉，返回原始列表避免白屏
        if (result.isEmpty()) {
            return cards
        }
        return result
    }
}
