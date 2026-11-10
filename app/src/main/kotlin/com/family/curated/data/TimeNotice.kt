package com.family.curated.data

import android.content.Context
import android.util.Log
import android.widget.Toast
import java.util.Calendar

/**
 * 剩余时间预告（OPT-05）。
 *
 * 纯计算在 [TimeNoticeMath]，这里只负责读本地状态、去重和弹提示。
 * 不新增线程 / 定时器：播放页沿用已有的 700ms ticker，外部应用沿用 KidApp 的 1 秒 tick。
 * 受 `features.remainingNotice` 控制，写坏开关也不抛异常。
 */
object TimeNotice {

    private const val TAG = "TimeNotice"

    @Volatile private var extNotified = false
    @Volatile private var lastSessionActive = false

    fun enabled(): Boolean = Extras.feature("remainingNotice")

    fun remaining(now: Long = ClockGuard.now()): TimeNoticeMath.Left {
        val sch = Extras.schedule()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return TimeNoticeMath.compute(
            usedMs = WatchClock.todayMs(),
            limitMinutes = sch?.dailyLimitMinutes ?: 0,
            tempMs = Extras.tempRemainingMs(),
            remoteLockMs = Extras.remoteLockRemainingMs(),
            scheduleEnabled = sch?.enabled == true,
            minuteOfDay = minute,
            windows = if (sch == null) emptyList() else sch.windowsAt(cal)
        )
    }

    /** 播放页右上角常显小字；开关关掉时返回空串，界面自己隐藏 */
    fun label(left: TimeNoticeMath.Left): String =
        if (enabled()) TimeNoticeMath.label(left) else ""

    /**
     * 播放页每 700ms 调一次。按「今天是否已经提示过这条阈值」去重：
     * 暂停、退出重进、跨剧集都不会重复刷屏。
     */
    fun tickPlayer(left: TimeNoticeMath.Left): String? {
        if (!enabled()) return null
        if (left.kind == TimeNoticeMath.Kind.NONE ||
            left.kind == TimeNoticeMath.Kind.REMOTE
        ) return null
        val tag = TimeNoticeMath.thresholdTag(left.ms) ?: return null
        if (Extras.noticeFired(tag)) return null
        Extras.markNoticeFired(tag)
        // 一开始就不足 1 分钟：5 分钟那条也一并标掉，只弹一条
        if (tag == "1min") Extras.markNoticeFired("5min")
        return TimeNoticeMath.noticeText(tag, left.ms)
    }

    /**
     * KidApp 每秒调。孩子在第三方应用里时 `current == null`，播放页的提示看不到，
     * 所以这里单独看「放行窗口 / 每日限额」谁先剩 2 分钟，用系统 Toast 浮在他的界面上。
     * 一次外部会话只提示一次。
     */
    fun tickExternal(ctx: Context) {
        if (!enabled()) return
        val active = Extras.appLaunchActive()
        if (active && !lastSessionActive) extNotified = false   // 新会话
        lastSessionActive = active
        if (!active || extNotified) return
        // 门禁已经关了（时段到点 / 限额用尽）就该被拉回来了，不必再提示
        if (!GateKeeper.status().open) return

        val launchLeft = Extras.appLaunchRemainingMs()
        val limitLeft = remaining()
        val near = (launchLeft in 1L..TimeNoticeMath.EXT_NOTICE_MS) ||
            (limitLeft.kind == TimeNoticeMath.Kind.LIMIT &&
                limitLeft.ms in 1L..TimeNoticeMath.EXT_NOTICE_MS)
        if (!near) return
        extNotified = true
        try {
            Toast.makeText(ctx, "还有 2 分钟就到时间啦，准备回到小剧场", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "外部应用提示失败: ${e.message}")
        }
    }
}
