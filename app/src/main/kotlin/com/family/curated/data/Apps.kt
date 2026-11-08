package com.family.curated.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个「应用入口」。spec 可以是包名，也可以是 "包名/完整 Activity 类名"
 * （同一个应用有多个入口时用后者）。label 是家长自定义显示名，留空用系统应用名。
 */
data class AppEntry(val spec: String, val label: String = "") {
    val pkg: String get() = spec.substringBefore('/')
    fun toJson(): JSONObject = JSONObject().apply {
        put("package", spec)
        if (label.isNotBlank()) put("label", label)
    }
}

/**
 * 儿童锁下允许出现的第三方应用入口，对应 tv.json 的 "apps" 段。
 *
 * 显示哪些应用以「电视上家长菜单的勾选」为准（存本地），apps.entries 只是首次安装
 * 或家长点「恢复清单默认」时使用的初始列表，方便手机 / PC 的网页控制台预置。
 * 孩子点开应用后，前台守卫在 maxMinutes 分钟内不拉回；超时、按 Home/返回、
 * 或中途跳到别的应用都会回到小剧场。
 */
data class AppsConfig(
    val enabled: Boolean = false,
    val title: String = "应用",
    /** true = 只在今天必看全部完成后才解锁应用分区 */
    val showAfterDaily: Boolean = true,
    /** 单次进入外部应用的最长放行时间 */
    val maxMinutes: Int = 30,
    val entries: List<AppEntry> = emptyList()
) {
    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("title", title)
        put("showAfterDaily", showAfterDaily)
        put("maxMinutes", maxMinutes)
        put("entries", JSONArray().also { arr -> entries.forEach { arr.put(it.toJson()) } })
    }.toString()

    companion object {
        fun fromJson(s: String?): AppsConfig = try {
            parse(JSONObject(s ?: "{}"))
        } catch (e: Exception) {
            AppsConfig()
        }

        fun parse(o: JSONObject?): AppsConfig {
            if (o == null) return AppsConfig()
            val arr = o.optJSONArray("entries") ?: JSONArray()
            val list = ArrayList<AppEntry>(arr.length())
            for (i in 0 until arr.length()) {
                when (val v = arr.opt(i)) {
                    is String -> if (v.isNotBlank()) list.add(AppEntry(v.trim()))
                    is JSONObject -> {
                        val spec = v.optString("package").ifBlank { v.optString("pkg") }.trim()
                        if (spec.isNotBlank()) list.add(AppEntry(spec, v.optString("label").trim()))
                    }
                }
            }
            return AppsConfig(
                enabled = o.optBoolean("enabled", false),
                title = o.optString("title").ifBlank { "应用" },
                showAfterDaily = o.optBoolean("showAfterDaily", true),
                maxMinutes = o.optInt("maxMinutes", 30).coerceIn(1, 240),
                entries = list.distinctBy { it.spec }
            )
        }
    }
}

/** 枚举电视上可启动的应用，供家长勾选；同时提供图标与启动 Intent */
object AppCatalog {

    data class Option(val spec: String, val label: String)

    /** 没有允许的应用时显示的占位卡入口；点它需要 PIN，孩子按了也进不去 */
    const val PARENT_ENTRY = "__parent__"

    /** 桌面 / 设置 / 安装器 / 应用商店 / 可看全量资源的播放器，不给家长误勾 */
    private val BLOCKED_EXACT = setOf(
        "com.android.settings", "com.android.vending", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.permissioncontroller",
        "com.jamdeo.tv.vod", "app.orchard.tv",
        "com.dangbeimarket", "com.tcl.browser",
        "org.courville.nova", "com.mygithub0.tvbox0.osdX", "com.mdxgh.tv"
    )
    private val BLOCKED_WORDS = listOf(
        "settings", "launcher", "packageinstaller", "permissioncontroller",
        "systemui", "setupwizard", "inputmethod",
        "systeminfo", "catlog", "logcat"
    )

    private fun blocked(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p in BLOCKED_EXACT || BLOCKED_WORDS.any { p.contains(it) }
    }

    fun installed(ctx: Context): List<Option> {
        val pm = ctx.packageManager
        val homes = try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val all = try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
        } catch (e: Exception) {
            emptyList()
        }
        val out = LinkedHashMap<String, Option>()
        for (r in all) {
            val pkg = r.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName || pkg in homes || blocked(pkg)) continue
            val label = try { r.loadLabel(pm).toString().trim() } catch (e: Exception) { "" }
            if (label.isBlank() || label == pkg) continue
            out.putIfAbsent(pkg, Option(pkg, label))
        }
        return out.values.sortedBy { it.label }
    }

    fun launchIntent(pm: PackageManager, spec: String): Intent? = try {
        if (spec.contains('/')) {
            val cmp = ComponentName.unflattenFromString(spec)
            if (cmp == null) null
            else Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cmp)
        } else {
            pm.getLaunchIntentForPackage(spec)
        }
    } catch (e: Exception) {
        null
    }

    fun icon(pm: PackageManager, spec: String): Drawable? = try {
        pm.getApplicationIcon(spec.substringBefore('/'))
    } catch (e: Exception) {
        null
    }

    fun label(pm: PackageManager, spec: String): String? = try {
        pm.getApplicationLabel(
            pm.getApplicationInfo(spec.substringBefore('/'), 0)
        ).toString()
    } catch (e: Exception) {
        null
    }
}

/**
 * 尽力判断当前前台应用包名：只在系统授予了「使用情况访问权限」时有效。
 * 没授权就返回 null，守卫按「无法判定」处理（仍受 maxMinutes 超时兜底），
 * 不影响普通安装。想启用可以在电视上执行：
 *   adb shell appops set com.family.curated GET_USAGE_STATS allow
 */
object AppGuard {
    fun foregroundPackage(ctx: Context): String? {
        if (!hasUsageAccess(ctx)) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return try {
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 20_000L, now)
            var pkg: String? = null
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    !e.packageName.isNullOrBlank()
                ) pkg = e.packageName
            }
            pkg
        } catch (e: Exception) {
            null
        }
    }

    private fun hasUsageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (ops == null) false
        else ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }
}
