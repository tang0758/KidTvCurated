package com.family.curated.data

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * 外部应用会话状态机（ADR-01）。唯一 owner，负责「从应用入口点开优酷到回来」这一整段的计时。
 *
 * 三条硬规矩：
 *  - 不新增线程 / 定时器 / 服务，由 `KidApp.tick` 的 1 秒节拍驱动；
 *  - 账写到 `watch_<day>`，与播放页同一本账，`GateKeeper` 一行都不用改；
 *  - `settle()` 幂等，三个结束信号（探针 LEFT、放行窗口到期、自己界面回前台）都走它。
 *
 * 有 usage 授权走 A（每 5 秒入账 + 结算时用系统累计值纠偏）；没授权自动降级 C
 * （进入即预扣 maxMinutes，结算时退还差额），**绝不出现少扣**。
 */
object ExtWatchTracker {

    private const val TAG = "ExtWatchTracker"
    /** 入账节流，对齐播放页「满几秒才写盘」的习惯 */
    private const val FLUSH_MS = 5_000L
    /** 异常超长会话兜底：4 小时无论如何结算一次，避免状态卡死 */
    private const val MAX_SESSION_MS = 4 * 60 * 60_000L

    private enum class Mode { OFF, EXACT, ESTIMATE }

    private var pkg: String? = null
    private var startRt = 0L
    private var flushed = 0L
    private var baseline = 0L
    private var preCharged = 0L
    private var mode = Mode.OFF

    fun active(): Boolean = pkg != null

    /** 给设置页看的「当前能力」，不是当前会话状态（会话结束后 mode 会复位） */
    fun modeText(ctx: Context): String = when {
        !Extras.feature("extWatchMs") -> "已关闭"
        ExtUsageProbe.hasUsageAccess(ctx) -> "精确计时（已授权）"
        else -> "估算扣时（未授权）"
    }

    /** `MainActivity.launchAllowedApp()` 在 startActivity **之前**调用 */
    @Synchronized
    fun onLaunch(ctx: Context, p: String, maxMinutes: Int) {
        settle(ctx)                      // 上一次没结清的先结清，绝不叠加
        if (!Extras.feature("extWatchMs")) {
            mode = Mode.OFF
            Log.i(TAG, "外部会话 $p · features.extWatchMs=false，不计时")
            return
        }
        WatchClock.stopCurrent()         // 播放页若忘了 stop，这里收掉，防重复计时
        pkg = p
        startRt = SystemClock.elapsedRealtime()
        flushed = 0L
        baseline = 0L
        preCharged = 0L
        if (ExtUsageProbe.hasUsageAccess(ctx)) {
            mode = Mode.EXACT
            baseline = ExtUsageProbe.foregroundTodayMs(ctx, p)
            Log.i(TAG, "外部会话开始 $p · 精确计时，系统基线 ${baseline / 1000}s")
        } else {
            mode = Mode.ESTIMATE
            val ms = maxMinutes.coerceIn(1, 240) * 60_000L
            WatchClock.preCharge(ms)
            preCharged = ms
            Log.i(TAG, "外部会话开始 $p · 无授权，预扣 ${ms / 60_000} 分钟")
        }
    }

    /** `KidApp.tick` 调用，位置必须在 `clearAppLaunch()` **之前**（ADR-01 4.2） */
    @Synchronized
    fun tick(ctx: Context) {
        val p = pkg ?: return
        if (mode == Mode.OFF) return
        val elapsed = (SystemClock.elapsedRealtime() - startRt).coerceAtLeast(0L)
        val probe = ExtUsageProbe.probe(ctx, p, startRt)
        if (probe == ExtUsageProbe.State.LEFT ||
            !Extras.appLaunchActive() ||
            elapsed > MAX_SESSION_MS
        ) {
            settle(ctx)
            return
        }
        // UNKNOWN 保持会话：优酷前台静置 5 分钟没有新事件，不等于孩子退出了
        if (mode != Mode.EXACT) return
        if (elapsed - flushed >= FLUSH_MS) {
            val delta = elapsed - flushed
            flushed = elapsed
            WatchClock.add(delta, ext = true)
        }
    }

    /**
     * 结算并复位。幂等：先把 pkg 置空，三个结束信号重复触发也不会二次入账。
     * @return 本次会话最终认定的毫秒数
     */
    @Synchronized
    fun settle(ctx: Context? = null): Long {
        val p = pkg ?: return 0L
        pkg = null
        val est = (SystemClock.elapsedRealtime() - startRt).coerceAtLeast(0L)
        val sys = if (mode == Mode.EXACT && ctx != null) {
            (ExtUsageProbe.foregroundTodayMs(ctx, p) - baseline).coerceAtLeast(0L)
        } else 0L
        // 系统值偶发跳变时封顶，避免一次异常把当天限额打满
        val finalMs = ExtClockMath.calibrate(est, sys).coerceAtMost(est + 60_000L)
        when (mode) {
            Mode.ESTIMATE -> {
                val diff = preCharged - finalMs
                if (diff > 0) WatchClock.refund(diff)
                else if (diff < 0) WatchClock.add(-diff, ext = true)
            }
            Mode.EXACT -> {
                val delta = finalMs - flushed
                if (delta > 0) WatchClock.add(delta, ext = true)
                // 负差额不强退：dayMax 只增不减是防改日期绕过的，宁可多算几秒
                else if (delta < 0) Log.i(TAG, "结算比已入账少 ${-delta / 1000}s，按就高不就低保留")
            }
            Mode.OFF -> {}
        }
        Log.i(
            TAG,
            "外部会话结算 $p · ${finalMs / 1000}s" +
                "（估算 ${est / 1000}s / 系统 ${sys / 1000}s / 模式 $mode）"
        )
        reset()
        return finalMs
    }

    /** 应用启动失败（已卸载等）：必须全额退还预扣，ADR-01 EX-18 */
    @Synchronized
    fun onLaunchFailed(ctx: Context) = settle(ctx)

    private fun reset() {
        pkg = null
        startRt = 0L
        flushed = 0L
        baseline = 0L
        preCharged = 0L
        mode = Mode.OFF
    }
}
