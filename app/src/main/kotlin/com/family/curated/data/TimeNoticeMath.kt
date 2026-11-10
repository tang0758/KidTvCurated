package com.family.curated.data

/**
 * 「还剩多久」的纯计算部分（OPT-05）。不碰 Android API，方便单测。
 *
 * 孩子真正被锁住的时刻，是下面几条里**最早**的那一条：
 *   远程锁定 > 临时放行结束 > min(每日限额用尽, 当前时段结束)
 * 只报「今日限额还剩多少」是不够的 —— 时段 20:30 结束、限额还剩 2 小时时，
 * 说「还剩 2 小时」是在骗孩子。
 */
object TimeNoticeMath {

    enum class Kind { NONE, LIMIT, WINDOW, TEMP, REMOTE }

    data class Left(val ms: Long, val kind: Kind)

    /** 表示该约束不存在（不限分钟 / 全天可看） */
    const val NONE_LEFT = Long.MAX_VALUE

    fun compute(
        usedMs: Long,
        limitMinutes: Int,
        tempMs: Long,
        remoteLockMs: Long,
        scheduleEnabled: Boolean,
        minuteOfDay: Int,
        windows: List<TimeWindow>
    ): Left {
        if (remoteLockMs > 0L) return Left(0L, Kind.REMOTE)
        if (tempMs > 0L) return Left(tempMs, Kind.TEMP)

        val limitMinLeft = if (limitMinutes > 0) {
            ((limitMinutes * 60_000L - usedMs + 59_999L) / 60_000L)
        } else NONE_LEFT
        val windowMinLeft = if (!scheduleEnabled || windows.isEmpty()) NONE_LEFT
        else minutesToWindowEnd(minuteOfDay, windows)

        val mins = minOf(limitMinLeft, windowMinLeft)
        if (mins == NONE_LEFT) return Left(0L, Kind.NONE)
        val ms = mins.coerceAtLeast(0L) * 60_000L
        val kind = if (windowMinLeft < limitMinLeft) Kind.WINDOW else Kind.LIMIT
        return Left(ms, kind)
    }

    /**
     * 从当前分钟到「正在使用的那个时间窗」结束还有多少分钟。
     * `endMin <= startMin` 表示跨零点（例如 20:00-01:00），零点之后属于同一段的后半截。
     * 返回 [NONE_LEFT] 表示没落在任何窗口里（正常不会发生，此时交给 GateKeeper 处理）。
     */
    fun minutesToWindowEnd(minuteOfDay: Int, windows: List<TimeWindow>): Long {
        var best = NONE_LEFT
        for (w in windows) {
            val left = if (w.endMin <= w.startMin) {
                when {
                    minuteOfDay >= w.startMin -> (24 * 60 - minuteOfDay + w.endMin).toLong()
                    minuteOfDay < w.endMin -> (w.endMin - minuteOfDay).toLong()
                    else -> continue
                }
            } else {
                if (minuteOfDay in w.startMin until w.endMin) (w.endMin - minuteOfDay).toLong()
                else continue
            }
            if (left < best) best = left
        }
        return best
    }

    /** 播放页右上角常显的小字 */
    fun label(left: Left): String = when (left.kind) {
        Kind.NONE -> ""
        Kind.REMOTE -> "家长已暂停观看"
        else -> if (left.ms <= 0L) "时间到啦"
        else when (left.kind) {
            Kind.TEMP -> "临时放行还剩 ${minutesText(left.ms)}"
            Kind.LIMIT -> "今天还剩 ${minutesText(left.ms)}"
            Kind.WINDOW -> "还能看 ${minutesText(left.ms)}"
            else -> ""
        }
    }

    fun minutesText(ms: Long): String {
        val min = ((ms + 59_999L) / 60_000L).coerceAtLeast(1L)
        return if (min >= 60) "${min / 60} 小时 ${min % 60} 分钟" else "$min 分钟"
    }

    /**
     * 当前应该提示哪一条阈值。5 分钟 / 1 分钟各一次；
     * 直接落到「不足 1 分钟」时只返回 1min，由调用方把 5min 一并标记为已读，
     * 避免同一次进入连弹两条 Toast。
     */
    fun thresholdTag(ms: Long): String? = when {
        ms <= 0L -> null
        ms <= 60_000L -> "1min"
        ms <= 300_000L -> "5min"
        else -> null
    }

    /** 文案按实际剩余时间说，别在进入页面时只剩 2 分钟却说「还剩 5 分钟」 */
    fun noticeText(tag: String, ms: Long): String = when (tag) {
        "1min" -> "还有不到 1 分钟就到时间啦，看完这一段就休息吧"
        else -> "还剩 ${minutesText(ms)}，准备一下哦"
    }

    /** 外部应用会话结束前 2 分钟提醒（计划里的「放行结束前 2 分钟」） */
    const val EXT_NOTICE_MS = 120_000L
}
