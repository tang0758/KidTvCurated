package com.family.curated.data

import kotlin.random.Random

/**
 * 每日单词打卡纯计算逻辑（FR-35 / OPT-34 · 艾宾浩斯遗忘曲线）。
 * 严禁依赖 android.* / org.json.*，保证纯 JVM JUnit 可直接单测。
 */
object WordCheckInMath {

    const val DEFAULT_DAILY_COUNT = 10
    const val GRADUATE_STAGE = 5

    /** 艾宾浩斯间隔天数表：阶段 1..4 分别隔 1, 2, 4, 7 天返场，第 5 次通过毕业（不再显示） */
    private val STAGE_INTERVAL_DAYS = intArrayOf(1, 1, 2, 4, 7, 3650)

    enum class Action { KNOW, UNKNOWN, GRADUATE }

    data class MemoryState(
        val stage: Int = 0,       // 0=未熟/遗忘, 1..4=认识阶梯, 5=🉐不再显示
        val dueDayNum: Long = 0L, // yyyyMMdd 整数，0 表示从未学过
        val mastery: CardMastery = CardMastery.UNMARKED
    ) {
        val isGraduated: Boolean get() = stage >= GRADUATE_STAGE
        val isNew: Boolean get() = dueDayNum == 0L && stage == 0 && mastery == CardMastery.UNMARKED
    }

    data class TransitionResult(
        val nextStage: Int,
        val nextDueDayNum: Long,
        val nextMastery: CardMastery
    )

    /** 计算答题后的记忆阶段与下次到期日 */
    fun nextMemory(curStage: Int, action: Action, todayDay: String): TransitionResult {
        val s = curStage.coerceIn(0, GRADUATE_STAGE)
        return when (action) {
            Action.GRADUATE -> TransitionResult(
                nextStage = GRADUATE_STAGE,
                nextDueDayNum = dayToNum(addDays(todayDay, STAGE_INTERVAL_DAYS[GRADUATE_STAGE])),
                nextMastery = CardMastery.MASTERED
            )
            Action.KNOW -> {
                val ns = (s + 1).coerceAtMost(GRADUATE_STAGE)
                val gap = STAGE_INTERVAL_DAYS[ns]
                TransitionResult(
                    nextStage = ns,
                    nextDueDayNum = dayToNum(addDays(todayDay, gap)),
                    nextMastery = if (ns >= GRADUATE_STAGE) CardMastery.MASTERED else CardMastery.UNMARKED
                )
            }
            Action.UNKNOWN -> {
                // 点「💔 不认识」退 2 阶（最低 0），并且明天立刻返场巩固
                val ns = (s - 2).coerceAtLeast(0)
                TransitionResult(
                    nextStage = ns,
                    nextDueDayNum = dayToNum(addDays(todayDay, 1)),
                    nextMastery = CardMastery.LEARNING
                )
            }
        }
    }

    /**
     * 按艾宾浩斯遗忘曲线挑选当日打卡单词：
     *  1. 💔 不认识 / Stage 0 且已到期（或曾标记 LEARNING）最优先复习
     *  2. 💗 认识阶梯 1..4 已到期词（dueDayNum <= todayDayNum），复习总名额最多占 count - 3（留给新词）
     *  3. 未学过的新词（isNew）
     *  4. 若仍不足 count，先从未到期的 Stage 1..4 按到期日从近到远补齐；只有全库都「🉐 不再显示」时才兜底抽温习，不卡死门禁
     */
    fun pickDailyWords(
        allWords: List<LearningCard>,
        memoryMap: Map<String, MemoryState>,
        todayDay: String,
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

        val todayNum = dayToNum(todayDay)
        val unknownDue = ArrayList<LearningCard>()
        val reviewDue = ArrayList<Pair<LearningCard, Long>>()
        val newWords = ArrayList<LearningCard>()
        val notDueYet = ArrayList<Pair<LearningCard, Long>>()
        val graduated = ArrayList<LearningCard>()

        for (c in unique) {
            val id = LearningMath.cardId(c)
            val mem = memoryMap[id] ?: MemoryState()
            when {
                mem.isGraduated -> graduated.add(c)
                mem.isNew -> newWords.add(c)
                mem.mastery == CardMastery.LEARNING || mem.stage == 0 -> {
                    if (mem.dueDayNum == 0L || mem.dueDayNum <= todayNum) {
                        unknownDue.add(c)
                    } else {
                        notDueYet.add(c to mem.dueDayNum)
                    }
                }
                mem.dueDayNum <= todayNum -> reviewDue.add(c to mem.dueDayNum)
                else -> notDueYet.add(c to mem.dueDayNum)
            }
        }

        // 到期越久越靠前；未到期则到期越近越靠前
        reviewDue.sortBy { it.second }
        notDueYet.sortBy { it.second }

        val rng = Random(seed)
        if (graduated.size > 1) graduated.shuffle(rng)

        val picked = ArrayList<LearningCard>(count)
        val pickedIds = HashSet<String>()
        fun tryAdd(c: LearningCard): Boolean {
            if (picked.size >= count) return false
            val id = LearningMath.cardId(c)
            if (pickedIds.add(id)) {
                picked.add(c)
                return true
            }
            return false
        }

        // 若有新词可用，给复习词设上限以保证每天都有新词推进（至少留 3 个或 30% 名额给新词）
        val reserveForNew = if (newWords.isNotEmpty() && count >= 4) (count * 3 / 10).coerceAtLeast(3) else 0
        val reviewCap = (count - reserveForNew).coerceAtLeast(1)

        for (c in unknownDue) {
            if (picked.size >= reviewCap) break
            tryAdd(c)
        }
        for ((c, _) in reviewDue) {
            if (picked.size >= reviewCap) break
            tryAdd(c)
        }
        for (c in newWords) {
            if (picked.size >= count) break
            tryAdd(c)
        }
        // 如果新词不够补满，再放开复习名额把剩余已到期但被限流的复习词补进来
        for (c in unknownDue) {
            if (picked.size >= count) break
            tryAdd(c)
        }
        for ((c, _) in reviewDue) {
            if (picked.size >= count) break
            tryAdd(c)
        }
        for ((c, _) in notDueYet) {
            if (picked.size >= count) break
            tryAdd(c)
        }
        // 全库都已「🉐 不再显示」或未到期词仍不足时才兜底，防止卡死门禁
        for (c in graduated) {
            if (picked.size >= count) break
            tryAdd(c)
        }
        return picked
    }

    /** 兼容旧调用的简化重载 */
    fun pickDailyWords(
        allWords: List<LearningCard>,
        masteryMap: Map<String, CardMastery>,
        count: Int = DEFAULT_DAILY_COUNT,
        seed: Long = 0L
    ): List<LearningCard> {
        val memMap = masteryMap.mapValues { (_, m) ->
            when (m) {
                CardMastery.LEARNING -> MemoryState(stage = 0, dueDayNum = 0L, mastery = m)
                CardMastery.MASTERED -> MemoryState(stage = GRADUATE_STAGE, dueDayNum = 99991231L, mastery = m)
                CardMastery.UNMARKED -> MemoryState(stage = 0, dueDayNum = 0L, mastery = m)
            }
        }
        return pickDailyWords(allWords, memMap, "2026-01-01", count, seed)
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

    fun dayToNum(day: String): Long {
        if (!StateMerge.isDayKey(day)) return 0L
        return day.replace("-", "").toLongOrNull() ?: 0L
    }

    /** 把 "苹果 · 我吃一个苹果。" 拆成 (单词中文, 例句中文翻译) */
    fun splitZhNote(note: String?): Pair<String, String> {
        val raw = (note ?: "").trim()
        if (raw.isEmpty()) return "" to ""
        for (sep in listOf("·", "|", "\n")) {
            val k = raw.indexOf(sep)
            if (k >= 0) {
                return raw.substring(0, k).trim() to raw.substring(k + 1).trim()
            }
        }
        return raw to ""
    }

    fun addDays(day: String, delta: Int): String {
        if (!StateMerge.isDayKey(day) || delta <= 0) return day
        var y = day.substring(0, 4).toInt()
        var m = day.substring(5, 7).toInt()
        var d = day.substring(8, 10).toInt()
        var left = delta
        while (left > 0) {
            val dim = daysInMonth(y, m)
            if (d + left <= dim) {
                d += left
                break
            }
            left -= (dim - d + 1)
            d = 1
            m += 1
            if (m > 12) {
                m = 1
                y += 1
            }
        }
        return "%04d-%02d-%02d".format(y, m, d)
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
