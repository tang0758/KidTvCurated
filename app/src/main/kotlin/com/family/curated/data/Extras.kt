package com.family.curated.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 打卡、时长、勋章、防跳看等新玩法的本地存档。
 * 和 Store 分开存，互不影响旧的完成/进度记录。
 */
object Extras {

    private const val PREF = "kid_extras"
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences = sp

    fun day(now: Long = System.currentTimeMillis()): String = synchronized(fmt) { fmt.format(Date(now)) }

    // ---------- 打卡墙 ----------

    fun stamps(): MutableSet<String> =
        HashSet(p().getStringSet("stamps", emptySet()) ?: emptySet())

    fun isCheckedIn(date: String): Boolean = stamps().contains(date)

    fun markCheckIn(now: Long = System.currentTimeMillis()) {
        val d = day(now)
        val s = stamps()
        if (d in s) return
        s.add(d)
        p().edit().putStringSet("stamps", s).apply()
    }

    /** 连续打卡天数：今天没打但昨天打了，仍按昨天的连击算 */
    fun streak(): Int {
        val s = stamps()
        if (s.isEmpty()) return 0
        val cal = Calendar.getInstance()
        if (day(cal.timeInMillis) !in s) cal.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0
        while (day(cal.timeInMillis) in s) {
            n++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return n
    }

    fun bestStreak(): Int {
        val dates = stamps().mapNotNull {
            runCatching { synchronized(fmt) { fmt.parse(it) } }.getOrNull()
        }.sorted()
        if (dates.isEmpty()) return 0
        var best = 1
        var cur = 1
        var prev = dates[0]
        for (i in 1 until dates.size) {
            val d = dates[i]
            val diffMillis = d.time - prev.time
            cur = if (diffMillis in 86_000_000..90_000_000) cur + 1 else 1
            if (cur > best) best = cur
            prev = d
        }
        return best
    }

    // ---------- 播放时长 ----------

    fun watchMsToday(): Long = p().getLong("watch_" + day(), 0L)

    fun addWatchMs(ms: Long) {
        if (ms <= 0) return
        val k = "watch_" + day()
        p().edit().putLong(k, p().getLong(k, 0L) + ms).apply()
    }

    fun watchMsWeek(): Long {
        val cal = Calendar.getInstance()
        var sum = 0L
        for (i in 0..6) {
            sum += p().getLong("watch_" + day(cal.timeInMillis), 0L)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun watchMsTotal(): Long = p().all
        .filterKeys { it.startsWith("watch_") }
        .values.sumOf { (it as? Long) ?: 0L }

    fun videosToday(): Int = p().getInt("vids_" + day(), 0)

    fun addVideo() {
        val k = "vids_" + day()
        p().edit().putInt(k, p().getInt(k, 0) + 1).apply()
    }

    fun videosTotal(): Int = p().all
        .filterKeys { it.startsWith("vids_") }
        .values.sumOf { (it as? Int) ?: 0 }

    // ---------- 防跳看：每个视频「真正看到过」的最远位置 ----------

    fun frontier(key: String): Long = p().getLong("front_$key", 0L)

    fun setFrontier(key: String, ms: Long) {
        p().edit().putLong("front_$key", ms).apply()
    }

    // ---------- 时段 / PIN / 通过比例 ----------

    fun schedule(): Schedule? = Schedule.fromJson(p().getString("schedule", null))

    fun saveSchedule(s: Schedule?) {
        p().edit().putString("schedule", s?.toJson()).apply()
    }

    fun pin(): String = p().getString("pin", "1234") ?: "1234"

    fun savePin(v: String) = p().edit().putString("pin", v).apply()

    fun passRatio(): Float = p().getFloat("passRatio", 0.92f)

    fun savePassRatio(v: Float) = p().edit().putFloat("passRatio", v).apply()

    // ---------- 儿童锁 / 家长临时放行 ----------

    fun kiosk(): Kiosk = Kiosk.fromJson(p().getString("kiosk", null))

    fun saveKiosk(v: Kiosk) = p().edit().putString("kiosk", v.toJson()).apply()

    /** 家长临时放行的截止时间；在此之前前台守卫不会把应用拉回来 */
    fun kioskExitUntil(): Long = p().getLong("kioskExitUntil", 0L)

    fun kioskExitActive(): Boolean = System.currentTimeMillis() < kioskExitUntil()

    fun grantKioskExit(minutes: Int) =
        p().edit().putLong(
            "kioskExitUntil",
            System.currentTimeMillis() + minutes.coerceIn(1, 24 * 60) * 60_000L
        ).apply()

    fun clearKioskExit() = p().edit().remove("kioskExitUntil").apply()

    // ---------- 家长允许的应用入口 ----------

    fun apps(): AppsConfig = AppsConfig.fromJson(p().getString("apps", null))

    fun saveApps(v: AppsConfig) = p().edit().putString("apps", v.toJson()).apply()

    /** 家长在电视上勾选过没？没勾选过就用清单 apps.entries 作为初始显示列表 */
    private fun selectedApps(): Set<String>? =
        if (p().contains("appsSelected")) p().getStringSet("appsSelected", emptySet()) ?: emptySet()
        else null

    fun visibleApps(cfg: AppsConfig = apps()): Set<String> =
        selectedApps() ?: cfg.entries.map { it.spec }.toSet()

    fun saveSelectedApps(v: Set<String>) =
        p().edit().putStringSet("appsSelected", HashSet(v)).apply()

    fun clearSelectedApps() = p().edit().remove("appsSelected").apply()

    /** 孩子点开一个允许的应用后的临时放行窗口 */
    fun grantAppLaunch(pkg: String, minutes: Int) = p().edit()
        .putString("appLaunchPkg", pkg)
        .putLong(
            "appLaunchUntil",
            System.currentTimeMillis() + minutes.coerceIn(1, 240) * 60_000L
        ).apply()

    fun appLaunchPackage(): String = p().getString("appLaunchPkg", "") ?: ""

    fun appLaunchUntil(): Long = p().getLong("appLaunchUntil", 0L)

    fun appLaunchActive(): Boolean =
        appLaunchPackage().isNotBlank() && System.currentTimeMillis() < appLaunchUntil()

    fun clearAppLaunch() = p().edit()
        .remove("appLaunchUntil").remove("appLaunchPkg").apply()

    // ---------- 锁屏学习角 ----------

    /** 没同步过清单时用内置题库，保证第一次装好就能显示 */
    fun learning(): Learning = Learning.fromJson(p().getString("learning", null))

    fun saveLearning(v: Learning) = p().edit().putString("learning", v.toJson()).apply()

    /** 家长临时放行的截止时间（毫秒） */
    fun tempUntil(): Long = p().getLong("tempUntil", 0L)

    fun grantTemp(minutes: Int) {
        p().edit().putLong("tempUntil", System.currentTimeMillis() + minutes * 60_000L).apply()
    }

    fun clearTemp() = p().edit().remove("tempUntil").apply()

    // ---------- 勋章 ----------

    fun seenBadges(): MutableSet<String> =
        HashSet(p().getStringSet("badges_seen", emptySet()) ?: emptySet())

    fun markBadgesSeen(ids: Collection<String>) {
        val s = seenBadges()
        s.addAll(ids)
        p().edit().putStringSet("badges_seen", s).apply()
    }
}
