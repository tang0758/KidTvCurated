package com.family.curated.data

import java.util.Locale

/**
 * 家长在电视上手动输入的「可看时段」：解析、显示、写回三种格式互转。
 *
 * 为什么单独一个文件：这段逻辑原来埋在 1094 行的 `SettingsActivity` 里（后来搬到 `TimeGroup`），
 * 而它偏偏是**最容易错、又最难实机验**的一块 —— 家长敲 `1800-2000` 与 `18:00-20:00`
 * 必须等价，`22:00-01:00` 必须真的跨到第二天凌晨。放在 `data` 包里的纯函数才能上单测
 * （纯 JVM 单测里 `android.*` 与 `org.json` 都是桩，一碰就 `RuntimeException: Stub!`）。
 *
 * 三条边界语义，都是历史行为，别顺手改：
 *  - **空字符串 / 只写了「全天」** → 返回空列表，表示那天全天可用（不是「不允许」）；
 *  - **一个合法时段都没解析出来** → 返回 `null`，调用方提示「格式不对」并且**不写回**；
 *  - **`start == end`** 视为跨零点，等价于全天（`TimeWindow.contains` 的既有判定）。
 */
object WindowMath {

    /**
     * 一行里认的写法：`1800-2000`、`18:00-20:00`、`7:30~11:30`、`18:00至20:00`、`09:00–11:30`、
     * `18：00－20：00`（中文输入法下的全角冒号与全角减号）。
     * 小时 1~2 位（`800` 也认）。
     * 多段之间不用管分隔符：`findAll` 会把每段各自揪出来，逗号 / 顿号 / 空格 / 换行都能用。
     */
    private val RANGE = Regex("(\\d{1,2})[:：]?(\\d{2})\\s*[-~至–—－]\\s*(\\d{1,2})[:：]?(\\d{2})")

    /**
     * 解析家长输入的一行文字。
     *
     * @param allDayWords 界面上「全天」的写法（可能不止一种：`全天` / `全天不限`）。
     *   **由调用方从 `strings.xml` 传进来** —— 这里不写死中文，否则文案就没法集中管理。
     * @return 空列表 = 全天；`null` = 格式不对（调用方应提示并放弃写回）
     */
    fun parse(text: String, allDayWords: List<String> = emptyList()): List<TimeWindow>? {
        val t = text.trim()
        if (t.isEmpty() || allDayWords.any { it == t }) return emptyList()
        val out = RANGE.findAll(t).map { m ->
            TimeWindow(
                minuteOfDay(m.groupValues[1], m.groupValues[2]),
                minuteOfDay(m.groupValues[3], m.groupValues[4])
            )
        }.toList()
        return if (out.isEmpty()) null else out
    }

    /** 小时 / 分钟各自夹到合法范围：`25:00` 当 `23:00`，`18:99` 当 `18:59`，不抛异常 */
    private fun minuteOfDay(h: String, m: String): Int =
        h.toInt().coerceIn(0, 23) * 60 + m.toInt().coerceIn(0, 59)

    /** 写回 `tv.json` 用：`["18:00-20:00", "21:00-21:30"]` */
    fun toJsonStrings(w: List<TimeWindow>): List<String> =
        w.map { "${hhmm(it.startMin)}-${hhmm(it.endMin)}" }

    /** 显示用：`18:00-20:00、21:00-21:30`；空列表返回 [allDay]（同样是调用方给的文案） */
    fun text(w: List<TimeWindow>, allDay: String, separator: String = "、"): String =
        if (w.isEmpty()) allDay else w.joinToString(separator) { "${hhmm(it.startMin)}-${hhmm(it.endMin)}" }

    /** 输入框预填用（紧凑写法，少点按键）：`1800-2000,2100-2130` */
    fun compact(w: List<TimeWindow>): String =
        w.joinToString(",") { "${compactHm(it.startMin)}-${compactHm(it.endMin)}" }

    /** `18:05`。用 `Locale.ROOT`：某些系统语言下 `%d` 会输出当地数字，家长会看不懂 */
    fun hhmm(min: Int): String = String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60)

    /** `1805` */
    fun compactHm(min: Int): String = String.format(Locale.ROOT, "%02d%02d", min / 60, min % 60)
}
