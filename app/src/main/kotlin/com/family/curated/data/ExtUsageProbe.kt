package com.family.curated.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.util.Calendar

/**
 * 外部应用会话探针（ADR-01 的 A 方案）。
 *
 * 只回答两件事：
 *  1. 目标应用现在还在不在前台（[probe]，三态，绝不用「查不到」冒充「已离开」）；
 *  2. 系统记录的它「今天累计在前台多久」（[foregroundTodayMs]，用于结算纠偏）。
 *
 * 没有「使用情况访问权限」时全部退化为 UNKNOWN / 0，由 [ExtWatchTracker]
 * 走 C 方案（预扣 + 退还），不会出现「少扣」。
 */
object ExtUsageProbe {

    /** 事件窗口上限：超过 30 分钟没有新事件就按 UNKNOWN 保持会话，靠放行窗口到期结算 */
    private const val MAX_EVENT_WINDOW_MS = 30 * 60_000L

    enum class State { STILL_IN, LEFT, UNKNOWN }

    fun hasUsageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        ops?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    /**
     * 看「本次会话开始至今」最后一条 MOVE_TO_FOREGROUND 属于谁。
     *
     * 只看包名聚合，不逐条判 MOVE_TO_BACKGROUND：优酷内部 Activity 切换会产生
     * 同包的 BACKGROUND + FOREGROUND，那不算离开。真正离开时系统一定会给
     * 别的包（桌面 / 小剧场）一条 FOREGROUND 事件。
     */
    fun probe(ctx: Context, pkg: String, startRt: Long): State {
        if (!hasUsageAccess(ctx)) return State.UNKNOWN
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return State.UNKNOWN
        return try {
            val span = (SystemClock.elapsedRealtime() - startRt)
                .coerceAtLeast(0L).coerceAtMost(MAX_EVENT_WINDOW_MS)
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - span - 5_000L, now + 1_000L)
            var lastFg: String? = null
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    !e.packageName.isNullOrBlank()
                ) lastFg = e.packageName
            }
            when (lastFg) {
                null -> State.UNKNOWN   // 静置无事件 / ROM 裁剪：信息缺失 ≠ 离开
                pkg -> State.STILL_IN
                else -> State.LEFT
            }
        } catch (e: Exception) {
            State.UNKNOWN
        }
    }

    /**
     * 系统当天累计前台时长（ms）。部分 ROM 要退到后台才刷新，所以只用于结算纠偏，
     * 绝不拿它做秒级显示。查不到返回 0，[ExtClockMath.calibrate] 会自动用估算值。
     */
    fun foregroundTodayMs(ctx: Context, pkg: String): Long {
        if (!hasUsageAccess(ctx)) return 0L
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L
        return try {
            val cal = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis() + 1_000L
            ) ?: return 0L
            stats.filter { it.packageName == pkg }
                .maxOfOrNull { it.totalTimeInForeground } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
