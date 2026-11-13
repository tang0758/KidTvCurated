package com.family.curated.data

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration

/**
 * OPT-30: 1080p 屏幕密度锁定。
 * 针对海信 VIDAA 及其他 Android TV 偶现非标 DPI / 720p 密度导致 UI 变形的问题，
 * 按 1920x1080 基准设计（设计基准宽 960dp / 密度 2.0f）自适应修正 displayMetrics。
 */
object DensityAdjuster {
    private var appDensity = 0f
    private var appScaledDensity = 0f

    fun apply(activity: Activity, app: Application) {
        val appMetrics = app.resources.displayMetrics
        if (appDensity == 0f) {
            appDensity = appMetrics.density
            appScaledDensity = appMetrics.scaledDensity
            app.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    if (newConfig.fontScale > 0) {
                        appScaledDensity = app.resources.displayMetrics.scaledDensity
                    }
                }
                override fun onLowMemory() {}
            })
        }

        // 以标准 1080p 宽度 1920px (960dp) 为目标密度设计
        val targetDensity = appMetrics.widthPixels.toFloat() / 960f
        val targetScaledDensity = targetDensity * (appScaledDensity / appDensity)
        val targetDensityDpi = (160 * targetDensity).toInt()

        activity.resources.displayMetrics.density = targetDensity
        activity.resources.displayMetrics.scaledDensity = targetScaledDensity
        activity.resources.displayMetrics.densityDpi = targetDensityDpi
    }
}
