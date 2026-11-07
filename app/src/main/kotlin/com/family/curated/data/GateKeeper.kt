package com.family.curated.data

import java.util.Calendar

/**
 * 全局「现在能不能看」判定。
 * 三种情况会锁屏：不在允许时段、当天时长用尽、以及两者叠加。
 */
object GateKeeper {

    enum class Reason { OK, TEMP, OUTSIDE, LIMIT }

    data class Status(
        val open: Boolean,
        val reason: Reason,
        val headline: String,
        val detail: String,
        val secondsLeft: Long,
        val usedMinutes: Int,
        val limitMinutes: Int
    )

    fun status(now: Long = System.currentTimeMillis()): Status {
        val sch = Extras.schedule()
        val usedMs = Extras.watchMsToday()
        val usedMin = (usedMs / 60_000L).toInt()

        if (sch == null || !sch.enabled) {
            return Status(true, Reason.OK, "", "", 0L, usedMin, 0)
        }
        val limit = sch.dailyLimitMinutes
        val temp = Extras.tempUntil()
        if (now < temp) {
            return Status(
                true, Reason.TEMP, "临时放行中",
                "家长已临时放行，剩余 ${((temp - now) / 60_000L) + 1} 分钟",
                (temp - now) / 1000L, usedMin, limit
            )
        }

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val windows = sch.windowsAt(cal)
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val inWindow = windows.isEmpty() || windows.any { it.contains(minute) }

        if (!inWindow) {
            val next = nextOpening(sch, now, includeToday = true)
            return Status(
                false, Reason.OUTSIDE, "现在不是看片时间", sch.lockMessage,
                ((next - now) / 1000L).coerceAtLeast(0L), usedMin, limit
            )
        }

        if (limit > 0 && usedMs >= limit * 60_000L) {
            val next = nextOpening(sch, now, includeToday = false)
            return Status(
                false, Reason.LIMIT, "今天的时间用完啦",
                "今天已经看了 $usedMin 分钟，明天再来吧",
                ((next - now) / 1000L).coerceAtLeast(0L), usedMin, limit
            )
        }

        return Status(true, Reason.OK, "", "", 0L, usedMin, limit)
    }

    /** 找下一个「可以开始看」的时刻 */
    private fun nextOpening(sch: Schedule, now: Long, includeToday: Boolean): Long {
        val base = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var best = Long.MAX_VALUE
        for (offset in 0..8) {
            if (offset == 0 && !includeToday) continue
            val day = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val wins = sch.windowsAt(day)
            val starts = ArrayList<Int>()
            if (wins.isEmpty()) starts.add(0)
            else wins.forEach {
                starts.add(it.startMin)
                if (it.endMin <= it.startMin) starts.add(0)  // 跨零点时段的后半段
            }
            for (m in starts) {
                val t = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, m / 60)
                    set(Calendar.MINUTE, m % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (t > now + 1000L && t < best) best = t
            }
        }
        return if (best == Long.MAX_VALUE) now + 24 * 3_600_000L else best
    }

    /** 大倒计时用的 HH:MM:SS，超过一天前面加天数 */
    fun hms(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val d = s / 86_400
        val h = (s % 86_400) / 3_600
        val m = (s % 3_600) / 60
        val sec = s % 60
        return if (d > 0) String.format("%d天 %02d:%02d:%02d", d, h, m, sec)
        else String.format("%02d:%02d:%02d", h, m, sec)
    }

    fun minutes(ms: Long): Int = (ms / 60_000L).toInt().coerceAtLeast(0)
}
