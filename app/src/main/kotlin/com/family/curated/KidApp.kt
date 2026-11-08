package com.family.curated

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.data.Store

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
    private var power: PowerManager? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                val st = GateKeeper.status()
                val a = current
                if (a is LockActivity) {
                    if (st.open) a.finish()
                } else if (a != null && !st.open &&
                    System.currentTimeMillis() - lastLockLaunch > 1500
                ) {
                    lastLockLaunch = System.currentTimeMillis()
                    a.startActivity(
                        Intent(a, LockActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
                enforceKiosk()
            } catch (e: Exception) {
                // 守卫不能把应用搞崩
            }
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * 第 3 道防线：Home 键系统不发给应用，但可以「事后」把孩子拉回来。
     * 只要自己的界面全都不在前台、屏幕还亮着、又不在家长放行窗口里，
     * 等 reentryMs 就把 MainActivity 拉回前台。配合「设为默认桌面」后基本感觉不到这一跳。
     */
    private fun enforceKiosk() {
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
        registerActivityLifecycleCallbacks(this)
        handler.postDelayed(tick, 1200)
    }

    override fun onActivityResumed(a: Activity) { current = a }
    override fun onActivityPaused(a: Activity) { if (current === a) current = null }
    override fun onActivityCreated(a: Activity, s: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
