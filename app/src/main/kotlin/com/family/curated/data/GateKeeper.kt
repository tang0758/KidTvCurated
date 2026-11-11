package com.family.curated.data

import java.util.Calendar

/**
 * 全局「现在能不能看」判定。
 * 三种情况会锁屏：不在允许时段、当天时长用尽、以及两者叠加。
 */
object GateKeeper {

    enum class Reason { OK, TEMP, OUTSIDE, LIMIT, REMOTE }

    data class Status(
        val open: Boolean,
        val reason: Reason,
        val headline: String,
        val detail: String,
        val secondsLeft: Long,
        val usedMinutes: Int,
        val limitMinutes: Int,
        /** 今天的上限里有多少是「完成必看」奖励出来的（OPT-10），界面用来解释为什么比配置的大 */
        val bonusMinutes: Int = 0
    )

    fun status(now: Long = ClockGuard.now()): Status {
        val sch = Extras.schedule()
        // 含还没落盘的零头：否则「到点」最多会晚 10 秒，孩子能多看一眼
        val usedMs = WatchClock.todayMs()
        val usedMin = (usedMs / 60_000L).toInt()
        // 完成必看的奖励直接加在今天上限上（不是临时放行窗口），和手动放行分开计
        val bonus = Extras.bonusMinutes()
        val limit = RewardMath.limitWith(sch?.dailyLimitMinutes ?: 0, bonus)

        // 家长在手机上点了「立即锁屏」：优先级最高，时段 / 临时放行都不能盖过它。
        // 只清 tempUntil 不留下这个状态的话，下一秒 KidApp 就会把刚弹出的锁屏 finish 掉。
        val remoteLeft = Extras.remoteLockRemainingMs()
        if (remoteLeft > 0L) {
            return Status(
                false, Reason.REMOTE, "家长已暂停观看",
                "家长点了「立即锁屏」。在电视上按菜单键 → PIN → 临时放行，即可解除",
                remoteLeft / 1000L, usedMin, limit, bonus
            )
        }

        if (sch == null || !sch.enabled) {
            return Status(true, Reason.OK, "", "", 0L, usedMin, 0, 0)
        }
        val tempLeft = Extras.tempRemainingMs()
        if (tempLeft > 0L) {
            return Status(
                true, Reason.TEMP, "临时放行中",
                "家长已临时放行，剩余 ${(tempLeft / 60_000L) + 1} 分钟",
                tempLeft / 1000L, usedMin, limit, bonus
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
                ((next - now) / 1000L).coerceAtLeast(0L), usedMin, limit, bonus
            )
        }

        if (limit > 0 && usedMs >= limit * 60_000L) {
            val next = nextOpening(sch, now, includeToday = false)
            return Status(
                false, Reason.LIMIT, "今天的时间用完啦",
                if (bonus > 0) "今天已经看了 $usedMin 分钟（含奖励 $bonus 分钟），明天再来吧"
                else "今天已经看了 $usedMin 分钟，明天再来吧",
                ((next - now) / 1000L).coerceAtLeast(0L), usedMin, limit, bonus
            )
        }

        return Status(true, Reason.OK, "", "", 0L, usedMin, limit, bonus)
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
