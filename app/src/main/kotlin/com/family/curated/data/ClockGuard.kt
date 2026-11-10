package com.family.curated.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时钟守卫：电视上的日期是可以被改的，而限额、打卡、时段全挂在日期上。
 *
 * 三件事：
 *  1. [now] —— 校正后的「现在」。墙上时间跳变（孩子把日期改到明天、自动对时回拨）时
 *     冻结在「上次可信时间 + 真实流逝」，日期键不会跟着跳，限额因此清零不了。
 *     时间被改回来时会自动解除冻结，不需要人工干预。
 *  2. [day] —— 统一日期键，所有 watch_/vids_/stamps 都用它，别的地方不要再 format 一遍。
 *  3. [noteUsage] / [dayMaxUsed] —— 当日已用量只增不减：哪怕本地记录被写回小值，
 *     也不会出现「今天莫名其妙又有时间了」。
 *
 * 已知限制：只在一次运行期间比对（elapsedRealtime 重启即归零）。
 * 「改日期 + 重启电视」这一组合识别不了，但那需要进系统设置再断电，不在本期威胁模型里。
 */
object ClockGuard {

    private const val TAG = "ClockGuard"
    private const val PREF = "kid_clock"
    /** 基线落盘节流：每次 now() 都写盘会把 SharedPreferences 写坏 */
    private const val PERSIST_EVERY_MS = 60_000L

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private lateinit var sp: SharedPreferences

    private var state = ClockMath.State()
    private var lastPersistRt = 0L

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // 重启后 elapsedRealtime 归零，旧的 goodRt 没有意义：只恢复墙上基线，
        // 第一次 now() 会用它重新建基线（偏差再大也先采信，开机时间是新的起点）
        val wall = sp.getLong("good_wall", 0L)
        state = ClockMath.State(goodWall = wall, goodRt = 0L, frozen = false)
        lastPersistRt = 0L
    }

    /** 校正后的当前时间。所有「现在几点」都应该走这里，不要直接 System.currentTimeMillis() */
    fun now(): Long {
        if (!::sp.isInitialized) return System.currentTimeMillis()
        val wall = System.currentTimeMillis()
        val rt = SystemClock.elapsedRealtime()
        val r = ClockMath.evaluate(wall, rt, state)
        state = r.state
        if (r.anomaly) {
            val n = sp.getInt("anomaly", 0) + 1
            sp.edit()
                .putInt("anomaly", n)
                .putLong("anomaly_wall", wall)
                .putLong("anomaly_at", System.currentTimeMillis())
                .apply()
            Log.w(TAG, "系统时间跳变（第 $n 次）：墙上 $wall，按可信时间 ${r.guarded} 继续")
        }
        if (r.recovered) {
            Log.i(TAG, "系统时间已恢复正常，解除冻结")
        }
        if (r.state.frozen) {
            // 冻结期间基线不动
        } else if (rt - lastPersistRt > PERSIST_EVERY_MS || lastPersistRt == 0L) {
            lastPersistRt = rt
            sp.edit().putLong("good_wall", r.state.goodWall).apply()
        }
        return r.guarded
    }

    fun day(now: Long = now()): String = synchronized(fmt) { fmt.format(Date(now)) }

    /** 是否处于「系统时间不可信」状态，设置页用它提示家长 */
    fun frozen(): Boolean = state.frozen

    fun anomalyCount(): Int = if (::sp.isInitialized) sp.getInt("anomaly", 0) else 0

    fun lastAnomalyAt(): Long = if (::sp.isInitialized) sp.getLong("anomaly_at", 0L) else 0L

    /** 家长在设置页点「按实际时间校准」：清冻结、清计数，重新采信当前时间 */
    fun reset() {
        if (!::sp.isInitialized) return
        state = ClockMath.State()
        sp.edit().putLong("good_wall", 0L).putInt("anomaly", 0).apply()
        lastPersistRt = 0L
    }

    // ---------- 当日已用量只增不减 ----------

    fun dayMaxUsed(day: String): Long = if (::sp.isInitialized) sp.getLong("daymax_$day", 0L) else 0L

    /** 记账后调用：当日用量取历史最大值，防止记录被改小 */
    fun noteUsage(day: String, usedMs: Long) {
        if (!::sp.isInitialized || usedMs <= 0) return
        val k = "daymax_$day"
        if (usedMs > sp.getLong(k, 0L)) sp.edit().putLong(k, usedMs).apply()
    }

    /**
     * 唯一的「合法下调」入口：外部应用预扣的退还（ADR-01 C 方案）。
     * 只由 `Extras.addWatchMs(refund=true)` 调用，额度受预扣值约束且结算幂等；
     * 孩子改日期 / 改记录都碰不到它，所以「只增不减」的防绕过语义没被打开。
     */
    fun lowerUsageFloor(day: String, usedMs: Long) {
        if (!::sp.isInitialized) return
        sp.edit().putLong("daymax_$day", usedMs.coerceAtLeast(0L)).apply()
    }

    /** 清掉 30 天前的 daymax，避免 prefs 无限长大 */
    fun prune(days: Int = 30) {
        if (!::sp.isInitialized) return
        val e = sp.edit()
        val keep = HashSet<String>()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now() }
        repeat(days) {
            keep.add(day(cal.timeInMillis))
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        sp.all.keys.filter { it.startsWith("daymax_") && it.removePrefix("daymax_") !in keep }
            .forEach { e.remove(it) }
        e.apply()
    }
}
