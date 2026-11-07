package com.family.curated

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            } catch (e: Exception) {
                // 守卫不能把应用搞崩
            }
            handler.postDelayed(this, 1000)
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
