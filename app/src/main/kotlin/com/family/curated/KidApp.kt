package com.family.curated

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.family.curated.data.AppGuard
import com.family.curated.data.Extras
import com.family.curated.data.ExtWatchTracker
import com.family.curated.data.GateKeeper
import com.family.curated.data.PlaylistSync
import com.family.curated.data.StateBackup
import com.family.curated.data.Store
import com.family.curated.data.TimeNotice
import com.family.curated.data.WatchClock

/**
 * 全局时间守卫：任何界面（包括剧集列表、播放页）到了不可用时段都会被锁屏盖住，
 * 时段一到自动收起。这样不用在每个 Activity 里各写一遍定时器。
 */
class KidApp : Application(), Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var current: Activity? = null
    private var lastLockLaunch = 0L
    private var backgroundSince = 0L
    private var lastPullback = 0L
    private var lastGatePullback = 0L
    private var power: PowerManager? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                // 先把观看时长结算掉，再判断时段：顺序反了，时段到点那一瞬间
                // 正在播放的一整段就会随着 clearAppLaunch() 丢掉（ADR-01 约束 2）
                WatchClock.tick()
                // 外部应用会话也必须在 clearAppLaunch() 之前结算，同样的理由
                ExtWatchTracker.tick(this@KidApp)
                val st = GateKeeper.status()
                // 孩子在第三方应用里时播放页不在前台，剩余时间预告由守卫代发
                // （放行窗口 / 每日限额结束前 2 分钟各一次，见 OPT-05）
                TimeNotice.tickExternal(this@KidApp)
                // 到了不可用时段，孩子即使正在家长允许的应用里也要收回来锁屏
                if (!st.open && Extras.appLaunchActive()) Extras.clearAppLaunch()
                val a = current
                if (a is LockActivity) {
                    if (st.open) a.finish()
                } else if (a != null && a !is SettingsActivity && !st.open &&
                    System.currentTimeMillis() - lastLockLaunch > 1500
                ) {
                    // 「家长控制」页例外：家长多半正是在改时段/上限，
                    // 锁屏盖上来会让人改不了（那一页本身已经要 PIN 才能进）
                    lastLockLaunch = System.currentTimeMillis()
                    a.startActivity(
                        Intent(a, LockActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
                // 孩子在第三方应用里时 current==null，上面的锁屏分支盖不到他。
                // 门禁兜底：把主界面拉回来，下一秒 tick 就会盖上锁屏。
                // 这条与 kioskPullback 无关 —— 那是「防切走」的体验开关，
                // 时段 / 限额是硬规则，不能被一个体验开关关掉。
                if (!st.open && a == null && !Extras.kioskExitActive()) pullbackForGate()
                enforceKiosk()
                // 清单自动同步：内部按 autoRefresh.intervalSeconds 节流，只有内容变了才重绘
                PlaylistSync.tick()
                // 打卡与进度备份：内部按 5 分钟节流，退到后台 / 打卡时会插队（OPT-09）
                StateBackup.tick()
            } catch (e: Exception) {
                // 守卫不能把应用搞崩
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** 时段外 / 限额用尽时，孩子在第三方应用里也要收回来锁屏 */
    private fun pullbackForGate() {
        val now = System.currentTimeMillis()
        if (now - lastGatePullback < 3000L) return
        lastGatePullback = now
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        } catch (e: Exception) {
            // 后台启动被 ROM 拦下时，下一秒 tick 会再试一次
        }
    }

    /**
     * 第 3 道防线：Home 键系统不发给应用，但可以「事后」把孩子拉回来。
     * 只要自己的界面全都不在前台、屏幕还亮着、又不在家长放行窗口里，
     * 等 reentryMs 就把 MainActivity 拉回前台。配合「设为默认桌面」后基本感觉不到这一跳。
     *
     * 受 `features.kioskPullback` 控制：个别电视上这套「拉回」会和投屏 / 系统弹窗打架，
     * 家长把开关改成 false 后 15 秒生效，不用重装 APK。
     */
    private fun enforceKiosk() {
        if (!Extras.feature("kioskPullback")) {
            backgroundSince = 0L
            return
        }
        val k = Extras.kiosk()
        if (!k.enabled || Extras.kioskExitActive() || current != null) {
            backgroundSince = 0L
            return
        }
        val pm = power ?: (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            .also { power = it }
        // 关屏 / 待机时不要自作主张把电视唤醒
        if (pm != null && !pm.isInteractive) {
            backgroundSince = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (Extras.appLaunchActive()) {
            // 家长明确允许的应用：放行窗口内不拉回；跳到别的应用则立刻收回来
            val fg = AppGuard.foregroundPackage(this)
            val allowedPkg = Extras.appLaunchPackage()
            if (fg.isNullOrBlank() || fg == allowedPkg || fg == packageName) {
                backgroundSince = 0L
                return
            }
            Extras.clearAppLaunch()
        }
        if (backgroundSince == 0L) {
            backgroundSince = now
            return
        }
        if (now - backgroundSince < k.reentryMs || now - lastPullback < 3000L) return
        lastPullback = now
        backgroundSince = 0L
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        } catch (e: Exception) {
            // 有些 ROM 在后台启动 Activity 会拦，忽略即可，默认桌面那层还在兜底
        }
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Extras.init(this)
        PlaylistSync.init(this)
        registerActivityLifecycleCallbacks(this)
        // 重装 / 恢复出厂后本地用量是 0，等于白送孩子一段额度（EX-13）。
        // 冷启动先把 NAS 备份里的「用量」静默合回来 —— 只会变大，不存在被滥用的方向。
        // 打卡与进度不在这里动：那可能是家长刚故意清空的，要问过才恢复（OPT-09）。
        PlaylistSync.submit { StateBackup.autoMergeUsage() }
        handler.postDelayed(tick, 1200)
    }

    override fun onActivityResumed(a: Activity) {
        current = a
        // 只要自己的界面回到前台，外部应用的会话就结束了。
        // 必须先结算再清放行：settle 的结束信号之一是 appLaunchActive()，
        // 顺序反了整段时长会丢（ADR-01 4.2 / 8 节）
        ExtWatchTracker.settle(a)
        if (Extras.appLaunchActive()) Extras.clearAppLaunch()
        // 从播放页 / 锁屏 / 别的应用回到主界面时，立刻查一次清单（下一次 1 秒 tick 生效）
        if (a is MainActivity) PlaylistSync.nudge()
    }
    override fun onActivityPaused(a: Activity) {
        if (current === a) {
            current = null
            // 应用退到后台（或跳去家长允许的第三方应用）时补一次备份：
            // 这是最自然的存档点，孩子下次开机看到的是完整记录（OPT-09）
            StateBackup.nudge("background")
        }
    }
    override fun onActivityCreated(a: Activity, s: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
