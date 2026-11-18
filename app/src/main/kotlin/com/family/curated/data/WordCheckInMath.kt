package com.family.curated.data

import kotlin.random.Random

/**
 * 每日单词打卡纯计算逻辑（FR-35 / OPT-34）。
 * 严禁依赖 android.* / org.json.*，保证纯 JVM JUnit 可直接单测。
 */
object WordCheckInMath {

    const val DEFAULT_DAILY_COUNT = 10

    /**
     * 按优先级挑出当日打卡单词：
     *  1. 不认识 / 重点再学（LEARNING）优先复习
     *  2. 未学过（UNMARKED）补充新词
     *  3. 若仍不足 count（例如词库已全部 MASTERED），从已掌握词中循环抽样，不卡死门禁
     */
    fun pickDailyWords(
        allWords: List<LearningCard>,
        masteryMap: Map<String, CardMastery>,
        count: Int = DEFAULT_DAILY_COUNT,
        seed: Long = 0L
    ): List<LearningCard> {
        if (count <= 0 || allWords.isEmpty()) return emptyList()

        val seen = LinkedHashSet<String>()
        val unique = ArrayList<LearningCard>()
        for (c in allWords) {
            if (c.type != CardType.WORD || c.title.isBlank()) continue
            val id = LearningMath.cardId(c)
            if (seen.add(id)) unique.add(c)
        }
        if (unique.isEmpty()) return emptyList()

        val learning = ArrayList<LearningCard>()
        val unmarked = ArrayList<LearningCard>()
        val mastered = ArrayList<LearningCard>()
        for (c in unique) {
            when (masteryMap[LearningMath.cardId(c)] ?: CardMastery.UNMARKED) {
                CardMastery.LEARNING -> learning.add(c)
                CardMastery.UNMARKED -> unmarked.add(c)
                CardMastery.MASTERED -> mastered.add(c)
            }
        }

        val rng = Random(seed)
        if (mastered.size > 1) mastered.shuffle(rng)

        val picked = ArrayList<LearningCard>(count)
        for (bucket in listOf(learning, unmarked, mastered)) {
            for (c in bucket) {
                if (picked.size >= count) return picked
                picked.add(c)
            }
        }
        return picked
    }

    /** 今日队列是否全部作答过一遍（空队列视为无需打卡，避免卡死门禁） */
    fun isDone(queueIds: List<String>, answeredIds: Set<String>): Boolean {
        if (queueIds.isEmpty()) return true
        return queueIds.all { it in answeredIds }
    }

    /** 并列前置门禁判定：视频每日必看（若有）与每日单词打卡（若启用）必须同时完成 */
    fun canUnlockDailyGate(
        hasVideoGate: Boolean,
        videoGateDone: Boolean,
        wordCheckInEnabled: Boolean,
        wordDoneToday: Boolean
    ): Boolean {
        val videoOk = !hasVideoGate || videoGateDone
        val wordOk = !wordCheckInEnabled || wordDoneToday
        return videoOk && wordOk
    }

    /** 计算单词打卡连续天数（与 Extras.streak 口径一致，仅使用纯日期字符串 yyyy-MM-dd） */
    fun streak(wordStamps: Set<String>, todayDay: String): Int {
        if (wordStamps.isEmpty() || !StateMerge.isDayKey(todayDay)) return 0
        var cur = if (todayDay in wordStamps) todayDay else prevDay(todayDay) ?: return 0
        var n = 0
        while (cur in wordStamps) {
            n++
            cur = prevDay(cur) ?: break
        }
        return n
    }

    internal fun prevDay(day: String): String? {
        if (!StateMerge.isDayKey(day)) return null
        var y = day.substring(0, 4).toInt()
        var m = day.substring(5, 7).toInt()
        var d = day.substring(8, 10).toInt()
        d -= 1
        if (d < 1) {
            m -= 1
            if (m < 1) {
                m = 12
                y -= 1
            }
            d = daysInMonth(y, m)
        }
        return "%04d-%02d-%02d".format(y, m, d)
    }

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if ((y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)) 29 else 28
        else -> 30
    }
}
