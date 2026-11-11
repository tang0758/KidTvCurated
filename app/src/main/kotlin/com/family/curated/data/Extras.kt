package com.family.curated.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Calendar
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
        // 日期键统一由 ClockGuard 提供，两个一起初始化，避免先后顺序出坑
        ClockGuard.init(ctx)
        featureMap = null
    }

    private fun p(): SharedPreferences = sp

    /** 当前日期键（yyyy-MM-dd）。走时钟守卫，改电视日期不会让它跳走 */
    fun day(now: Long = ClockGuard.now()): String = ClockGuard.day(now)

    /** 和日期键同一套校正时间的 Calendar（打卡连击、近 7 天统计都用它） */
    private fun calNow(): Calendar = Calendar.getInstance().apply { timeInMillis = ClockGuard.now() }

    // ---------- 打卡墙 ----------

    fun stamps(): MutableSet<String> =
        HashSet(p().getStringSet("stamps", emptySet()) ?: emptySet())

    fun isCheckedIn(date: String): Boolean = stamps().contains(date)

    fun markCheckIn(now: Long = ClockGuard.now()) {
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
        val cal = calNow()
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

    // ---------- 播放时长（统一由 WatchClock 记账，这里只读写） ----------

    /** 某天的真实用量。取 max(记录值, 历史最大值)：只增不减，改记录/改日期都不会让它变小 */
    fun watchMsOn(dayKey: String): Long {
        val actual = p().getLong("watch_$dayKey", 0L)
        return maxOf(actual, ClockGuard.dayMaxUsed(dayKey))
    }

    fun watchMsToday(): Long = watchMsOn(day())

    fun extMsToday(): Long = p().getLong("ext_" + day(), 0L)

    /**
     * 记账。ms 可以是负数（退预扣），结果不会小于 0。
     * [ext] = 这段时长来自外部应用（应用入口），另记一份明细到 `ext_<day>`。
     */
    fun addWatchMs(ms: Long, dayKey: String = day(), ext: Boolean = false, refund: Boolean = false) {
        if (ms == 0L) return
        val k = "watch_$dayKey"
        val cur = p().getLong(k, 0L)
        val next = (cur + ms).coerceAtLeast(0L)
        val e = p().edit().putLong(k, next)
        if (ext) {
            val ek = "ext_$dayKey"
            e.putLong(ek, (p().getLong(ek, 0L) + ms).coerceAtLeast(0L))
        }
        e.apply()
        if (refund && ms < 0) ClockGuard.lowerUsageFloor(dayKey, next)
        else ClockGuard.noteUsage(dayKey, next)
    }

    fun watchMsWeek(): Long {
        val cal = calNow()
        var sum = 0L
        for (i in 0..6) {
            sum += watchMsOn(day(cal.timeInMillis))
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
    fun kioskExitUntil(): Long = ClockGuard.now() + kioskExitRemainingMs()

    fun kioskExitRemainingMs(): Long = remaining("kioskExitRt", "kioskExitUntil")

    fun kioskExitActive(): Boolean = kioskExitRemainingMs() > 0L

    fun grantKioskExit(minutes: Int) =
        grant("kioskExitRt", "kioskExitUntil", minutes.coerceIn(1, 24 * 60))

    fun clearKioskExit() = p().edit().remove("kioskExitUntil").remove("kioskExitRt").apply()

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
    fun grantAppLaunch(pkg: String, minutes: Int) {
        grant("appLaunchRt", "appLaunchUntil", minutes.coerceIn(1, 240))
        p().edit().putString("appLaunchPkg", pkg).apply()
    }

    fun appLaunchPackage(): String = p().getString("appLaunchPkg", "") ?: ""

    fun appLaunchUntil(): Long = ClockGuard.now() + appLaunchRemainingMs()

    fun appLaunchRemainingMs(): Long = remaining("appLaunchRt", "appLaunchUntil")

    fun appLaunchActive(): Boolean =
        appLaunchPackage().isNotBlank() && appLaunchRemainingMs() > 0L

    fun clearAppLaunch() = p().edit()
        .remove("appLaunchUntil").remove("appLaunchRt").remove("appLaunchPkg").apply()

    // ---------- 锁屏学习角 ----------

    /** 没同步过清单时用内置题库，保证第一次装好就能显示 */
    fun learning(): Learning = Learning.fromJson(p().getString("learning", null))

    fun saveLearning(v: Learning) = p().edit().putString("learning", v.toJson()).apply()

    /** 家长临时放行的截止时间（毫秒，墙上时间，给界面显示用） */
    fun tempUntil(): Long = ClockGuard.now() + tempRemainingMs()

    fun tempRemainingMs(): Long = remaining("tempUntilRt", "tempUntil")

    fun grantTemp(minutes: Int) {
        // 0 分钟 = 立刻结束（远程「立即锁屏」用），不能当成"永久放行"
        if (minutes <= 0) { clearTemp(); return }
        grant("tempUntilRt", "tempUntil", minutes)
        // 家长在电视上主动放行，就是明确的「解除远程锁定」动作
        clearRemoteLock()
    }

    fun clearTemp() = p().edit().remove("tempUntil").remove("tempUntilRt").apply()

    // ---------- 远程控制（OPT-06）：家长在手机上点的「立即锁屏」 ----------

    /**
     * 只清 tempUntil 是不够的：如果本来就在允许时段、当日也没超额，
     * `GateKeeper.status()` 仍然是 open，下一秒 KidApp 会把刚弹出的锁屏 finish 掉。
     * 所以「立即锁屏」必须是一个门禁能读到的状态。
     *
     * 最长 8 小时：家长点的锁要一直锁到他自己解除，但不能因为控制文件丢了、
     * 家长忘了，让电视永久变砖。和放行倒计时一样存墙上 + 单调两份。
     */
    fun setRemoteLock(on: Boolean) {
        if (on) grant("remoteLockRt", "remoteLockUntil", 8 * 60)
        else clearRemoteLock()
    }

    fun clearRemoteLock() = p().edit()
        .remove("remoteLockUntil").remove("remoteLockRt").apply()

    fun remoteLockRemainingMs(): Long = remaining("remoteLockRt", "remoteLockUntil")

    fun remoteLockActive(): Boolean = remoteLockRemainingMs() > 0L

    // ---------- 剩余时间预告（OPT-05）：同一天每条阈值只提示一次 ----------

    fun noticeFired(tag: String): Boolean = p().getBoolean("notice_${day()}_$tag", false)

    fun markNoticeFired(tag: String) =
        p().edit().putBoolean("notice_${day()}_$tag", true).apply()

    // ---------- 记录备份（OPT-09） ----------

    /** 要备份的打卡记录 */
    fun backupStamps(): Set<String> = stamps()

    /** 要备份的长整数键：时长账本、进度、防跳看、最近点开。过滤规则在 StateMerge */
    fun backupLongs(): Map<String, Long> {
        val all = p().all
        val out = LinkedHashMap<String, Long>()
        all.forEach { (k, v) ->
            if (!StateMerge.isLongKey(k)) return@forEach
            val n = when (v) {
                is Long -> v
                is Int -> v.toLong()
                else -> null
            } ?: return@forEach
            out[k] = n
        }
        return out
    }

    /**
     * 把备份里的记录合回本地。**只升不降**：打卡取并集，长整数取大值。
     *
     * `watch_<day>` 调高之后必须同步 `ClockGuard.noteUsage()` —— 当日用量是
     * `max(watch_, daymax_)`，只改前者也能生效，但补一次 daymax 才不会在
     * 后续某个「记录被改小」的路径上又被放回去。
     */
    fun applyState(stamps: Set<String>, longs: Map<String, Long>) {
        if (stamps.isEmpty() && longs.isEmpty()) return
        val cur = p().all
        val e = p().edit()
        if (stamps.isNotEmpty()) {
            val merged = stamps()
            merged.addAll(stamps)
            e.putStringSet("stamps", merged)
        }
        val raisedWatch = HashMap<String, Long>()
        longs.forEach { (k, v) ->
            if (!StateMerge.isLongKey(k)) return@forEach
            val before = when (val b = cur[k]) {
                is Long -> b
                is Int -> b.toLong()
                else -> 0L
            }
            if (v <= before) return@forEach
            if (k.startsWith("vids_")) e.putInt(k, v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            else e.putLong(k, v)
            if (k.startsWith("watch_")) raisedWatch[k.removePrefix("watch_")] = v
        }
        e.apply()
        raisedWatch.forEach { (day, ms) -> ClockGuard.noteUsage(day, ms) }
    }

    // ---------- 放行倒计时的实现 ----------
    //
    // 截止时间存两份：墙上时间（给界面显示 / 跨重启还认）+ 单调时间 elapsedRealtime
    // （改电视日期骗不过它）。取两者剩余的最小值 —— 哪个先到就按哪个结束，
    // 既防「改日期续命」，也防「重启后 elapsedRealtime 归零导致放行永不结束」。

    private fun grant(rtKey: String, wallKey: String, minutes: Int) {
        val ms = minutes * 60_000L
        p().edit()
            .putLong(wallKey, System.currentTimeMillis() + ms)
            .putLong(rtKey, SystemClock.elapsedRealtime() + ms)
            .apply()
    }

    private fun remaining(rtKey: String, wallKey: String): Long {
        val byWall = (p().getLong(wallKey, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
        val rtDeadline = p().getLong(rtKey, 0L)
        if (rtDeadline <= 0L) return byWall
        val byRt = (rtDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        // 刚开机时 elapsedRealtime 很小，rtDeadline 会显得很远 → 取小的那个
        return minOf(byWall, byRt)
    }

    // ---------- 远程功能开关（tv.json 的 features 段） ----------

    @Volatile private var featureMap: Map<String, Boolean>? = null

    fun features(): Features = Features(featureCache())

    fun saveFeatures(v: Features) {
        featureMap = v.on
        p().edit().putString("features", v.toJsonString()).apply()
    }

    /**
     * 新功能总开关。缺字段、写错类型都读默认值（新功能默认关闭），绝不抛异常 ——
     * 这是「出问题改一行 JSON、15 秒恢复」的前提，开关本身不能成为新的故障点。
     */
    fun feature(name: String): Boolean = featureCache()[name] ?: Features.defaultOf(name)

    private fun featureCache(): Map<String, Boolean> =
        featureMap ?: Features.fromJson(p().getString("features", null)).on.also { featureMap = it }

    // ---------- 勋章 ----------

    fun seenBadges(): MutableSet<String> =
        HashSet(p().getStringSet("badges_seen", emptySet()) ?: emptySet())

    fun markBadgesSeen(ids: Collection<String>) {
        val s = seenBadges()
        s.addAll(ids)
        p().edit().putStringSet("badges_seen", s).apply()
    }
}
