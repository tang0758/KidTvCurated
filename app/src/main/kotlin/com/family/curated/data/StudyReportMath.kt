package com.family.curated.data

/**
 * 学情总览纯计算逻辑（纯 JVM，零 Android / org.json 依赖，便于 JUnit 单测）。
 *
 * 汇总四类卡片（英语单词 / 汉字 / 唐诗 / 名画）的掌握度分布、
 * 薄弱项（重点再学或到期需复习）以及近 7 天新掌握动态。
 */
object StudyReportMath {

    const val RECENT_DAYS_MS = 7L * 24L * 3_600_000L

    data class TypeSummary(
        val type: CardType,
        val total: Int,
        val mastered: Int,
        val learning: Int,
        val unmarked: Int
    ) {
        val percent: Int get() = if (total <= 0) 0 else (mastered * 100 / total).coerceIn(0, 100)
    }

    data class WeakItem(
        val card: LearningCard,
        val isMarkedLearning: Boolean,
        val stage: Int
    )

    data class RecentMastered(
        val card: LearningCard,
        val timestampMs: Long
    )

    data class Report(
        val byType: List<TypeSummary>,
        val weakItems: List<WeakItem>,
        val recentMastered: List<RecentMastered>
    ) {
        val totalCards: Int get() = byType.sumOf { it.total }
        val totalMastered: Int get() = byType.sumOf { it.mastered }
        val totalLearning: Int get() = byType.sumOf { it.learning }
    }

    fun build(
        words: List<LearningCard>,
        hanzi: List<LearningCard>,
        poems: List<LearningCard>,
        arts: List<LearningCard>,
        memories: Map<String, WordCheckInMath.MemoryState>,
        timestamps: Map<String, Long>,
        todayDayKey: String,
        nowMs: Long
    ): Report {
        val todayNum = WordCheckInMath.dayToNum(todayDayKey)
        val cutoff = nowMs - RECENT_DAYS_MS

        val groups = listOf(
            CardType.WORD to words,
            CardType.TEXT to hanzi,
            CardType.POEM to poems,
            CardType.ART to arts
        )

        val summaries = ArrayList<TypeSummary>(4)
        val weak = ArrayList<WeakItem>()
        val recent = ArrayList<RecentMastered>()

        for ((type, list) in groups) {
            var masteredCnt = 0
            var learningCnt = 0
            var unmarkedCnt = 0

            for (c in list) {
                val cid = LearningMath.cardId(c)
                val mem = memories[cid] ?: WordCheckInMath.MemoryState()
                val isMastered = mem.mastery == CardMastery.MASTERED || mem.isGraduated
                val isLearning = !isMastered && mem.mastery == CardMastery.LEARNING

                when {
                    isMastered -> {
                        masteredCnt++
                        val ts = timestamps[cid] ?: 0L
                        if (ts in cutoff..nowMs) {
                            recent.add(RecentMastered(c, ts))
                        }
                    }
                    isLearning -> {
                        learningCnt++
                        weak.add(WeakItem(c, isMarkedLearning = true, stage = mem.stage))
                    }
                    else -> {
                        unmarkedCnt++
                        // 艾宾浩斯已到期且尚未掌握的复习词/字也归入待巩固薄弱项
                        if (mem.stage > 0 && mem.dueDayNum > 0L && mem.dueDayNum <= todayNum) {
                            weak.add(WeakItem(c, isMarkedLearning = false, stage = mem.stage))
                        }
                    }
                }
            }

            summaries.add(
                TypeSummary(
                    type = type,
                    total = list.size,
                    mastered = masteredCnt,
                    learning = learningCnt,
                    unmarked = unmarkedCnt
                )
            )
        }

        // 薄弱项排序：明确标「重点再学」的排最前，再按类别顺序
        val sortedWeak = weak.sortedWith(
            compareByDescending<WeakItem> { it.isMarkedLearning }
                .thenBy { it.stage }
                .thenBy { it.card.type.ordinal }
        )

        val sortedRecent = recent.sortedByDescending { it.timestampMs }

        return Report(
            byType = summaries,
            weakItems = sortedWeak,
            recentMastered = sortedRecent
        )
    }
}
