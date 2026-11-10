package com.family.curated.data

import android.os.SystemClock
import android.util.Log

/**
 * 观看时长统一账本。全应用**只有一个**计时来源，避免同一段时间被记两次。
 *
 * 规则：
 *  - 任何要计时的东西先 [start] 拿一个 owner 令牌，结束时 [stop] 还回来。
 *    换 owner 会自动结算上一个（重进播放页忘了 stop 也不会重复计），并打一条日志。
 *  - 由 `KidApp` 的 1 秒 tick 驱动 [tick]，每 10 秒落盘一次；暂停 / 切后台时
 *    [stop] 会立刻把余下的零头结清，不会丢。
 *  - 跨零点按「累加时所在的日期」分账：`watch_<day>` 是总账，`ext_<day>` 是外部应用明细。
 *  - [preCharge] / [refund] 是给「外部应用计时」（ADR-01 的 C 方案）预留的：
 *    没有 usage 授权时先悲观预扣，结算后多扣的退回去，宁可多扣也不漏记。
 *
 * 时间一律用 `SystemClock.elapsedRealtime()` 差值，不用墙上时间：
 * 孩子把电视日期改来改去也不会影响已经看过的时长。
 */
object WatchClock {

    private const val TAG = "WatchClock"
    /** 落盘间隔：10 秒 */
    private const val FLUSH_MS = 10_000L
    /** 单次 tick 最多认 2.5 秒，防止主线程卡顿后一次性把一大段算进去 */
    private const val MAX_GAP_MS = 2_500L

    enum class Source { PLAYER, EXT }

    private var owner: String? = null
    private var source = Source.PLAYER
    private var pendingMs = 0L
    private var pendingDay = ""
    private var lastRt = 0L

    private var chargedMs = 0L
    private var chargedDay = ""

    fun ownerToken(): String? = owner

    fun running(): Boolean = owner != null

    @Synchronized
    fun start(token: String, src: Source = Source.PLAYER) {
        if (token.isBlank()) return
        val cur = owner
        if (cur == token) {
            // 同一来源重复 start（配置变化重建页面）：不清零、不换基线
            lastRt = SystemClock.elapsedRealtime()
            return
        }
        if (cur != null) {
            Log.w(TAG, "计时来源被抢占：$cur → $token（旧的一段先结清）")
            flushLocked()
        }
        owner = token
        source = src
        lastRt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun stop(token: String) {
        if (owner != token) return
        flushLocked()
        owner = null
        lastRt = 0L
    }

    /** 由 KidApp 的 1 秒 tick 调用 */
    @Synchronized
    fun tick() {
        if (owner == null) {
            lastRt = 0L
            return
        }
        val rt = SystemClock.elapsedRealtime()
        if (lastRt == 0L) {
            lastRt = rt
            return
        }
        val dt = (rt - lastRt).coerceIn(0L, MAX_GAP_MS)
        lastRt = rt
        if (dt <= 0L) return

        val today = ClockGuard.day()
        // 跨零点：先把前一天欠的结清，再在新的一天重新开始累计
        if (pendingDay.isNotEmpty() && pendingDay != today) flushLocked()
        if (pendingDay.isEmpty()) pendingDay = today

        pendingMs += dt
        if (pendingMs >= FLUSH_MS) flushLocked()
    }

    /** 立刻落盘（进入锁屏、退出播放、写清单前都可以调） */
    @Synchronized
    fun flush() = flushLocked()

    /** 还没落盘的「今天」部分，供 GateKeeper 精确判定到点 */
    @Synchronized
    fun pendingMsToday(): Long {
        if (pendingMs <= 0L) return 0L
        val today = ClockGuard.day()
        return if (pendingDay.isEmpty() || pendingDay == today) pendingMs else 0L
    }

    private fun flushLocked() {
        val ms = pendingMs
        val day = pendingDay
        pendingMs = 0L
        pendingDay = ""
        if (ms <= 0L || day.isEmpty()) return
        Extras.addWatchMs(ms, day, source == Source.EXT)
    }

    // ---------- 外部应用计时预留（ADR-01 C 方案：先扣后退） ----------

    /**
     * 悲观预扣：假设孩子会看满 [ms]，先记进总账，防止「看的时候不计时、到点不锁」。
     * 结算时用 [refund] 把没看的部分退回去。
     */
    @Synchronized
    fun preCharge(ms: Long) {
        if (ms <= 0L) return
        val day = ClockGuard.day()
        Extras.addWatchMs(ms, day, ext = true)
        chargedMs += ms
        chargedDay = day
        Log.i(TAG, "预扣 ${ms / 1000} 秒（${day}）")
    }

    /** 退还预扣。不传参 = 把这次预扣的全部退回 */
    @Synchronized
    fun refund(ms: Long = chargedMs) {
        if (ms <= 0L) return
        val day = if (chargedDay.isNotEmpty()) chargedDay else ClockGuard.day()
        Extras.addWatchMs(-ms, day, ext = true)
        chargedMs = (chargedMs - ms).coerceAtLeast(0L)
        if (chargedMs == 0L) chargedDay = ""
        Log.i(TAG, "退还 ${ms / 1000} 秒（${day}）")
    }

    /** 只读：现在账面上「今天看了多少」（含未落盘零头） */
    fun todayMs(): Long = Extras.watchMsToday() + pendingMsToday()
}
