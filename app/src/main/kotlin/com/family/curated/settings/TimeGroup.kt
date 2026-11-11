package com.family.curated.settings

import android.text.InputType
import com.family.curated.ParentTools
import com.family.curated.R
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.data.RewardMath
import com.family.curated.data.Schedule
import com.family.curated.data.TimeWindow
import com.family.curated.data.WindowMath
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「时间与时长」分组：每日上限、可看时段、时段总开关、完成必看后加时。
 *
 * 这四个字段都落在 tv.json 的 `schedule` 段里。改完立刻写回 NAS，
 * 网页编辑器打开的就是同一份，不存在「电视上改完、下次同步又被盖回去」。
 */
class TimeGroup(private val h: SettingsHost) : SettingsGroup {

    override fun rows(): List<Entry> {
        val sch = Extras.schedule()
        val st = GateKeeper.status()
        return buildList<Entry> {
            add(Entry.Group(h.str(R.string.set_group_time)))
            add(
                Entry.Row(
                    h.str(R.string.set_limit),
                    if (st.limitMinutes == 0) h.str(R.string.set_limit_hint_unlimited)
                    else if (st.bonusMinutes > 0) h.str(R.string.set_limit_hint_bonus, st.bonusMinutes)
                    else h.str(R.string.set_limit_hint_full),
                    if ((sch?.dailyLimitMinutes ?: 0) > 0) h.str(R.string.set_minutes, sch!!.dailyLimitMinutes)
                    else h.str(R.string.set_unlimited)
                ) { chooseLimit() }
            )
            add(
                Entry.Row(
                    h.str(R.string.set_window_weekday),
                    h.str(R.string.set_window_weekday_hint),
                    windowsText(sch?.weekday ?: emptyList())
                ) { chooseWindows(weekend = false) }
            )
            add(
                Entry.Row(
                    h.str(R.string.set_window_weekend),
                    h.str(R.string.set_window_weekend_hint),
                    windowsText(sch?.weekend ?: emptyList())
                ) { chooseWindows(weekend = true) }
            )
            add(
                Entry.Row(
                    h.str(R.string.set_window_enable),
                    if (sch?.enabled == true) h.str(R.string.set_window_enable_on)
                    else h.str(R.string.set_window_enable_off),
                    if (sch?.enabled == true) h.str(R.string.common_on) else h.str(R.string.common_off)
                ) { toggleSchedule() }
            )
            add(
                Entry.Row(
                    h.str(R.string.set_reward),
                    if ((sch?.rewardMinutes ?: 0) > 0) h.str(R.string.set_reward_hint_on, sch!!.rewardMinutes)
                    else h.str(R.string.set_reward_hint_off),
                    if ((sch?.rewardMinutes ?: 0) > 0) h.str(R.string.set_reward_value, sch!!.rewardMinutes)
                    else h.str(R.string.common_off)
                ) { chooseReward() }
            )
        }
    }

    // ---------------- 每日上限 ----------------

    private fun chooseLimit() {
        val cur = Extras.schedule()?.dailyLimitMinutes ?: 0
        // 清单里可能是 50 这种不在预设里的值：并进来，否则选项里看不到当前值、也打不了勾
        val values = (listOf(0, 20, 30, 45, 60, 90, 120) + cur).distinct().sorted()
        val labels = values.map {
            if (it == 0) h.str(R.string.set_unlimited) else h.str(R.string.set_minutes, it)
        } + h.str(R.string.set_custom)
        h.act.darkDialog()
            .setTitle(h.str(R.string.set_limit))
            .setItems(
                labels.mapIndexed { i, s -> (if (i < values.size && values[i] == cur) "✓ " else "   ") + s }
                    .toTypedArray()
            ) { _, which ->
                if (which == values.size) askCustomLimit(cur) else {
                    val v = values[which]
                    updateSchedule(h.str(R.string.set_limit)) { it.copy(dailyLimitMinutes = v, enabled = true) }
                }
            }
            .setNegativeButton(h.str(R.string.common_cancel), null)
            .show()
    }

    private fun askCustomLimit(cur: Int) {
        val input = h.act.inputField(
            hint = h.str(R.string.set_limit_input_hint),
            initial = if (cur > 0) cur.toString() else "",
            type = InputType.TYPE_CLASS_NUMBER
        )
        val dialog = h.act.darkDialog()
            .setTitle(h.str(R.string.set_limit_ask_title))
            .setView(input)
            .setPositiveButton(h.str(R.string.common_save)) { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v < 0 || v > 720) {
                    h.toast(h.str(R.string.set_limit_invalid), long = true)
                    return@setPositiveButton
                }
                updateSchedule(h.str(R.string.set_limit)) { it.copy(dailyLimitMinutes = v, enabled = true) }
            }
            .setNegativeButton(h.str(R.string.common_cancel), null)
            .create()
        dialog.show()
        ParentTools.submitOnEnter(input, dialog)
    }

    // ---------------- 完成必看加时 ----------------

    /**
     * 完成必看自动加时（OPT-10）。写进 schedule.rewardMinutes，0 = 关闭。
     * 注意它加的是「今天的上限」，不是临时放行窗口：做完任务先去吃饭，
     * 晚上回来这 N 分钟还在。家长手动放行（维护里的「临时放行」）是另一套计数，互不覆盖。
     */
    private fun chooseReward() {
        val cur = Extras.schedule()?.rewardMinutes ?: 0
        val values = (listOf(0, 5, 10, 15, 20, 30) + cur).distinct().sorted()
        val labels = values.map {
            if (it == 0) h.str(R.string.common_off) else h.str(R.string.set_reward_pick, it)
        } + h.str(R.string.set_custom)
        h.act.darkDialog()
            .setTitle(h.str(R.string.set_reward_title))
            .setItems(
                labels.mapIndexed { i, s -> (if (i < values.size && values[i] == cur) "✓ " else "   ") + s }
                    .toTypedArray()
            ) { _, which ->
                if (which == values.size) askCustomReward(cur)
                else updateSchedule(h.str(R.string.set_reward)) { it.copy(rewardMinutes = values[which]) }
            }
            .setNegativeButton(h.str(R.string.common_cancel), null)
            .show()
    }

    private fun askCustomReward(cur: Int) {
        val input = h.act.inputField(
            hint = h.str(R.string.set_reward_input_hint, RewardMath.MAX_REWARD_MINUTES),
            initial = if (cur > 0) cur.toString() else "",
            type = InputType.TYPE_CLASS_NUMBER
        )
        val dialog = h.act.darkDialog()
            .setTitle(h.str(R.string.set_reward_ask_title))
            .setView(input)
            .setPositiveButton(h.str(R.string.common_save)) { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v < 0 || v > RewardMath.MAX_REWARD_MINUTES) {
                    h.toast(h.str(R.string.set_reward_invalid, RewardMath.MAX_REWARD_MINUTES), long = true)
                    return@setPositiveButton
                }
                updateSchedule(h.str(R.string.set_reward)) { it.copy(rewardMinutes = v) }
            }
            .setNegativeButton(h.str(R.string.common_cancel), null)
            .create()
        dialog.show()
        ParentTools.submitOnEnter(input, dialog)
    }

    // ---------------- 可看时段 ----------------

    private fun chooseWindows(weekend: Boolean) {
        val presets = listOf(
            h.str(R.string.set_win_all_free) to emptyList<TimeWindow>(),
            h.str(R.string.set_win_morning) to listOf(TimeWindow(7 * 60, 8 * 60)),
            h.str(R.string.set_win_after_school) to listOf(TimeWindow(16 * 60, 19 * 60)),
            h.str(R.string.set_win_afternoon) to listOf(TimeWindow(17 * 60, 20 * 60)),
            h.str(R.string.set_win_evening) to listOf(TimeWindow(18 * 60, 20 * 60)),
            h.str(R.string.set_win_before_bed) to listOf(TimeWindow(19 * 60, 21 * 60)),
            h.str(R.string.set_win_split) to listOf(
                TimeWindow(9 * 60, 11 * 60 + 30),
                TimeWindow(16 * 60, 21 * 60)
            )
        )
        val cur = windowsText(currentWindows(weekend))
        val labels = presets.map { if (it.first == cur) "✓ ${it.first}" else "   ${it.first}" } +
            h.str(R.string.set_custom_indent)
        val dayName = if (weekend) h.str(R.string.set_days_weekend) else h.str(R.string.set_days_weekday)
        // 当前值不在预设里时（例如 17:30-20:30），标题里也写一遍，家长知道现在是什么
        val title =
            if (cur == h.str(R.string.set_window_all_day) || presets.any { it.first == cur })
                h.str(R.string.set_win_title, dayName)
            else h.str(R.string.set_win_title_now, dayName, cur)
        h.act.darkDialog()
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == presets.size) askCustomWindows(weekend)
                else setWindows(weekend, presets[which].second)
            }
            .setNegativeButton(h.str(R.string.common_cancel), null)
            .show()
    }

    private fun askCustomWindows(weekend: Boolean) {
        val initial = WindowMath.compact(currentWindows(weekend))
        val input = h.act.inputField(
            hint = h.str(R.string.set_win_input_hint),
            initial = initial
        )
        val dialog = h.act.darkDialog()
            .setTitle(
                if (weekend) h.str(R.string.set_win_custom_weekend) else h.str(R.string.set_win_custom_weekday)
            )
            .setView(input)
            .setPositiveButton(h.str(R.string.common_save)) { _, _ ->
                val parsed = parseWindows(input.text.toString())
                if (parsed == null) {
                    h.toast(h.str(R.string.set_win_invalid), long = true)
                    return@setPositiveButton
                }
                setWindows(weekend, parsed)
            }
            .setNegativeButton(h.str(R.string.common_cancel), null)
            .create()
        dialog.show()
        ParentTools.submitOnEnter(input, dialog)
    }

    /** 只改平日或只改周末，另一天原样保留（原来的周末可能是两段，别一次改动全抹掉） */
    private fun setWindows(weekend: Boolean, w: List<TimeWindow>) {
        val cur = Extras.schedule() ?: Schedule(enabled = false, pin = Extras.pin())
        val next = if (weekend) cur.copy(weekend = w, enabled = true)
        else cur.copy(weekday = w, enabled = true)
        h.save({ root ->
            val o = root.optJSONObject("schedule") ?: JSONObject().also { root.put("schedule", it) }
            putWindows(o, "weekday", next.weekday)
            putWindows(o, "weekend", next.weekend)
            o.put("enabled", true)
        }, { Extras.saveSchedule(next) },
            if (weekend) h.str(R.string.set_window_weekend) else h.str(R.string.set_window_weekday)
        )
    }

    private fun toggleSchedule() {
        val sch = Extras.schedule() ?: Schedule(pin = Extras.pin())
        val next = !sch.enabled
        updateSchedule(h.str(R.string.set_window_enable)) { it.copy(enabled = next) }
        if (next && sch.weekday.isEmpty() && sch.dailyLimitMinutes == 0) {
            h.toast(h.str(R.string.set_hint_no_limits), long = true)
        }
    }

    private fun currentWindows(weekend: Boolean): List<TimeWindow> {
        val s = Extras.schedule() ?: return emptyList()
        return if (weekend) s.weekend else s.weekday
    }

    // ---------------- 写回 schedule 段 ----------------

    /** 时段相关的字段都在 schedule 段里；没有就建一个 */
    private fun updateSchedule(label: String, transform: (Schedule) -> Schedule) {
        val cur = Extras.schedule() ?: Schedule(enabled = false, pin = Extras.pin())
        val next = transform(cur)
        h.save({ root ->
            val o = root.optJSONObject("schedule") ?: JSONObject().also { root.put("schedule", it) }
            o.put("enabled", next.enabled)
            putWindows(o, "weekday", next.weekday)
            putWindows(o, "weekend", next.weekend)
            o.put("dailyLimitMinutes", next.dailyLimitMinutes)
            if (next.rewardMinutes > 0) o.put("rewardMinutes", next.rewardMinutes)
            else o.remove("rewardMinutes")
            o.put("lockMessage", next.lockMessage)
            if (next.pin.isNotBlank()) o.put("pin", next.pin)
        }, { Extras.saveSchedule(next) }, label)
    }

    private fun putWindows(o: JSONObject, key: String, w: List<TimeWindow>) {
        o.put(key, JSONArray().also { a ->
            WindowMath.toJsonStrings(w).forEach { a.put(it) }
        })
    }

    // ---------------- 文案 / 解析（实现在 data/WindowMath，有单测） ----------------

    private fun windowsText(w: List<TimeWindow>): String =
        WindowMath.text(w, h.str(R.string.set_window_all_day))

    /** 「全天」在界面上有两种写法（`全天` / `全天不限`），都当成「全天」 */
    private fun parseWindows(text: String): List<TimeWindow>? = WindowMath.parse(
        text,
        listOf(h.str(R.string.set_window_all_day), h.str(R.string.set_win_all_free))
    )
}
