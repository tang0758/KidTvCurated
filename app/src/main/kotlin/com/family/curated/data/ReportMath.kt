package com.family.curated.data

/**
 * 周报的纯逻辑（OPT-08）：本周 7 天的时长序列、汇总、反复看、看到一半。
 *
 * 放在这里而不是直接写进 `ReportActivity`，原因和 [AutoNext] / [StateMerge] 一样：
 * 纯 JVM 单测里没有 `android.*` 也没有 `org.json`，一碰就 `RuntimeException: Stub!`。
 * 所以这一层只做算术，SharedPreferences / 布局 / 绘图留在 Activity 与 View 里。
 *
 * 日期算术**不用** `Calendar` 也不用 `java.time`：前者要带上时区，后者在 minSdk 24 上
 * 需要 desugaring。周报只关心「哪一天」，用 epoch day（1970-01-01 为第 0 天）最稳，
 * 顺带还能单测跨月、跨年、闰年二月这些边界。
 */
object ReportMath {

    const val WEEK_DAYS = 7

    /** 图表刻度以 15 分钟为最小格 */
    private const val SCALE_STEP_MS = 15 * 60_000L

    /** 一周里的一天 */
    data class DayRow(
        val date: String,     // 2026-11-09
        val weekday: String,  // 一
        val dayNum: Int,      // 9
        val totalMs: Long,
        val extMs: Long,
        val done: Boolean,    // 当天有没有盖章
        val isToday: Boolean,
        val future: Boolean
    ) {
        /** 小剧场自己算的部分（总时长减去第三方应用那段） */
        val ownMs: Long get() = (totalMs - extMs).coerceAtLeast(0L)
    }

    /** 一条播放进度，用来找「看到一半就退出」 */
    data class Progress(val key: String, val posMs: Long, val durMs: Long, val playedAt: Long)

    /** 结果行：「反复看」value = 次数，「看到一半」value = 百分比 */
    data class Named(val key: String, val title: String, val value: Int)

    // ---------------- 日期算术（纯整数，无时区） ----------------

    /** 公历 → epoch day（Howard Hinnant 的 days_from_civil） */
    fun toEpochDay(y: Int, m: Int, d: Int): Long {
        var yy = y.toLong()
        if (m <= 2) yy -= 1
        val mm = m.toLong()
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (mm + (if (mm > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** epoch day → 公历（days_from_civil 的逆运算） */
    fun fromEpochDay(epoch: Long): Triple<Int, Int, Int> {
        val z = epoch + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = mp + (if (mp < 10) 3 else -9)
        val year = (y + (if (m <= 2) 1 else 0)).toInt()
        return Triple(year, m.toInt(), d.toInt())
    }

    /** "2026-11-11" → epoch day；格式不对返回 null */
    fun parseDay(s: String?): Long? {
        val t = (s ?: "").trim()
        if (t.length != 10 || t[4] != '-' || t[7] != '-') return null
        val y = t.substring(0, 4).toIntOrNull() ?: return null
        val m = t.substring(5, 7).toIntOrNull() ?: return null
        val d = t.substring(8, 10).toIntOrNull() ?: return null
        if (y !in 2000..2100 || m !in 1..12 || d !in 1..31) return null
        return toEpochDay(y, m, d)
    }

    fun formatDay(epoch: Long): String {
        val (y, m, d) = fromEpochDay(epoch)
        return String.format("%04d-%02d-%02d", y, m, d)
    }

    /** 周一 = 0 … 周日 = 6。1970-01-01 是周四 */
    fun weekdayIndex(epoch: Long): Int = (((epoch + 3) % 7 + 7) % 7).toInt()

    private val WEEKDAY_CN = arrayOf("一", "二", "三", "四", "五", "六", "日")

    fun weekdayText(epoch: Long): String = WEEKDAY_CN[weekdayIndex(epoch)]

    /** 本周一那天的 epoch day */
    fun weekStart(epoch: Long): Long = epoch - weekdayIndex(epoch)

    /** 本周 7 天的日期（周一 → 周日）。解析失败时退化成只有今天一天 */
    fun weekDates(today: String): List<String> {
        val e = parseDay(today) ?: return listOf(today)
        val start = weekStart(e)
        return (0 until WEEK_DAYS).map { formatDay(start + it) }
    }

    /** 「11/09 - 11/15」，给标题下面那行小字用 */
    fun weekRangeText(today: String): String {
        val e = parseDay(today) ?: return today
        val start = weekStart(e)
        val end = start + WEEK_DAYS - 1
        val a = fromEpochDay(start)
        val b = fromEpochDay(end)
        return String.format("%02d/%02d - %02d/%02d", a.second, a.third, b.second, b.third)
    }

    // ---------------- 一周的时长序列 ----------------

    /**
     * 拼出本周的 7 行。
     *
     * [extMs] 会被夹到不超过 [watchMs]：`ext_<day>` 与 `watch_<day>` 是两个键，
     * 万一被手改进 prefs（或预扣退还算错）出现 ext > total，条形图会画出负数段。
     */
    fun buildWeek(
        today: String,
        watchMs: (String) -> Long,
        extMs: (String) -> Long,
        doneOf: (String) -> Boolean
    ): List<DayRow> {
        val dates = weekDates(today)
        val todayEpoch = parseDay(today)
        return dates.map { date ->
            val total = watchMs(date).coerceAtLeast(0L)
            val ext = extMs(date).coerceIn(0L, total)
            val epoch = parseDay(date)
            DayRow(
                date = date,
                weekday = weekdayText(epoch ?: 0L),
                dayNum = epoch?.let { fromEpochDay(it).third } ?: 0,
                totalMs = total,
                extMs = ext,
                done = doneOf(date),
                isToday = date == today,
                // 未来的日子不参与「平均每天」，否则周一就显示「平均 5 分钟」
                future = epoch != null && todayEpoch != null && epoch > todayEpoch
            )
        }
    }

    fun totalMs(rows: List<DayRow>): Long = rows.sumOf { it.totalMs }

    fun extMs(rows: List<DayRow>): Long = rows.sumOf { it.extMs }

    /** 有记录的天数（不是 7）：只按真正看过的天算平均，才不会天天被拉低 */
    fun activeDays(rows: List<DayRow>): Int = rows.count { it.totalMs > 0L }

    fun averageMs(rows: List<DayRow>): Long {
        val n = activeDays(rows)
        return if (n == 0) 0L else totalMs(rows) / n
    }

    fun doneDays(rows: List<DayRow>): Int = rows.count { it.done }

    fun isEmpty(rows: List<DayRow>): Boolean = totalMs(rows) == 0L && rows.none { it.done }

    /**
     * 纵轴最大值：至少覆盖当天上限（这样「用满了没有」一眼能看出来），
     * 再向上取整到 15 分钟，避免出现 37 分钟这种刻度。
     */
    fun scaleMax(rows: List<DayRow>, limitMs: Long = 0L): Long {
        val peak = maxOf(rows.maxOfOrNull { it.totalMs } ?: 0L, limitMs.coerceAtLeast(0L))
        if (peak <= 0L) return SCALE_STEP_MS
        return ((peak + SCALE_STEP_MS - 1) / SCALE_STEP_MS) * SCALE_STEP_MS
    }

    /** 第 [index] 天的条形高度占比（0..1），给自定义 View 用 */
    fun ratio(ms: Long, scaleMax: Long): Float =
        if (scaleMax <= 0L) 0f else (ms.toFloat() / scaleMax.toFloat()).coerceIn(0f, 1f)

    // ---------------- 反复看 / 看到一半 ----------------

    /**
     * 本周反复看：同一部在几天里被点开过，按天数倒序。
     * 只出现 1 天的不算「反复」；天数相同时按 key 排序，保证每次刷新顺序一致。
     */
    fun topRepeated(playsPerKey: Map<String, Int>, limit: Int = 3): List<Named> =
        playsPerKey.entries
            .filter { it.value >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit.coerceAtLeast(0))
            .map { Named(it.key, pathTitle(it.key), it.value) }

    /**
     * 「总看到一半就退出」：进度在 5% ~ 60% 之间。
     * 低于 5% 是点开就走（误点），高于 60% 多半还在看，都不符合「一半就退」。
     * 按最近点开时间倒序 —— 家长更关心「昨天又没看完」。
     */
    fun halfWatched(
        all: List<Progress>,
        limit: Int = 2,
        minRatio: Float = 0.05f,
        maxRatio: Float = 0.60f
    ): List<Named> = all.asSequence()
        .filter { it.durMs >= 60_000L && it.posMs > 0L }
        .map { it to (it.posMs.toFloat() / it.durMs.toFloat()) }
        .filter { (_, r) -> r >= minRatio && r <= maxRatio }
        .sortedByDescending { (p, _) -> p.playedAt }
        .take(limit.coerceAtLeast(0))
        .map { (p, r) -> Named(p.key, pathTitle(p.key), (r * 100).toInt()) }
        .toList()

    /** 只剥视频扩展名：应用入口的 key 是包名（`p:com.youku.phone`），剥了就只剩 `com.youku` */
    private val VIDEO_EXT = listOf(
        "mp4", "mkv", "avi", "ts", "mov", "rmvb", "webm", "m4v",
        "flv", "wmv", "mpg", "mpeg", "m2ts", "iso"
    )

    /**
     * 从路径派生的键里猜个名字：`p:/cartoon/小猪佩奇/01.mp4` → `01`。
     * 片单里家长自填的标题优先（[ReportActivity] 会用清单缓存先查一遍），
     * 这里只是兜底 —— 老记录对应的片子可能已经被移出片单了。
     */
    fun pathTitle(key: String): String {
        var s = key.trim()
        if (s.startsWith("p:")) s = s.substring(2)
        s = s.trimEnd('/')
        if (s.isEmpty()) return "(未命名)"
        var name = s.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot > 0 && name.substring(dot + 1).lowercase() in VIDEO_EXT) {
            name = name.substring(0, dot)
        }
        return name.ifBlank { "(未命名)" }
    }

    /** 分钟数的口语写法：0 → 「0 分钟」，90 → 「1 小时 30 分」 */
    fun durationText(ms: Long): String {
        val total = (ms / 60_000L).coerceAtLeast(0L).toInt()
        if (total < 60) return "$total 分钟"
        val h = total / 60
        val m = total % 60
        return if (m == 0) "$h 小时" else "$h 小时 $m 分"
    }
}
