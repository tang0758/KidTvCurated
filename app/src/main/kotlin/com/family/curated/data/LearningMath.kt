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
        val extras = ArrayList<Pair<Int, LearningCard>>()

        for (c in cards) {
            val id = cardId(c)
            val m = masteryMap[id] ?: CardMastery.UNMARKED
            when (m) {
                CardMastery.LEARNING -> {
                    extras.add(result.size to c)
                    result.add(c) // 权重 2.0：第 2 份错开插入，避免连续两张重复
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

        if (extras.isNotEmpty() && result.size >= 2) {
            val half = (result.size / 2).coerceAtLeast(1)
            for ((idxOffset, pair) in extras.withIndex()) {
                val (firstIdx, card) = pair
                val cid = cardId(card)
                var pos = (firstIdx + half + idxOffset) % (result.size + 1)
                var tries = 0
                while (tries < result.size + 1) {
                    val prevSame = pos > 0 && cardId(result[pos - 1]) == cid
                    val nextSame = pos < result.size && cardId(result[pos]) == cid
                    if (!prevSame && !nextSame) break
                    pos = (pos + 1) % (result.size + 1)
                    tries++
                }
                result.add(pos, card)
            }
        } else {
            extras.forEach { result.add(it.second) }
        }

        // 保底：若全部卡片都被过滤掉，返回原始列表避免白屏
        if (result.isEmpty()) {
            return cards
        }
        return result
    }

    /**
     * 古诗按句读自动分行：遇到逗号/句号/问号/感叹号/分号即断开，
     * 标点留在前一句末尾，避免两句并成一行在带配图的窄栏里折行难看。
     */
    fun splitPoemLines(lines: List<String>): List<String> {
        val breaks = setOf('，', '。', '？', '！', '；', ',', ';')
        val trailing = setOf('”', '’', '」', '』', '"', '\'')
        val out = ArrayList<String>()
        for (raw in lines) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            val buf = StringBuilder()
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                buf.append(ch)
                if (ch in breaks) {
                    while (i + 1 < s.length && s[i + 1] in trailing) {
                        i++
                        buf.append(s[i])
                    }
                    val part = buf.toString().trim()
                    if (part.isNotEmpty()) out.add(part)
                    buf.setLength(0)
                }
                i++
            }
            val rest = buf.toString().trim()
            if (rest.isNotEmpty()) out.add(rest)
        }
        return out
    }
}
