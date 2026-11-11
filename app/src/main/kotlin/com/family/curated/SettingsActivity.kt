package com.family.curated

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.curated.data.AppsConfig
import com.family.curated.data.ClockGuard
import com.family.curated.data.Diag
import com.family.curated.data.Extras
import com.family.curated.data.ExtWatchTracker
import com.family.curated.data.Features
import com.family.curated.data.GateKeeper
import com.family.curated.data.KioskKeys
import com.family.curated.data.PlaylistSync
import com.family.curated.data.PlaylistWriter
import com.family.curated.data.RemoteControl
import com.family.curated.data.Repo
import com.family.curated.data.RewardMath
import com.family.curated.data.Schedule
import com.family.curated.data.Smb
import com.family.curated.data.StateBackup
import com.family.curated.data.Store
import com.family.curated.data.TimeWindow
import com.family.curated.databinding.ActivitySettingsBinding
import com.family.curated.databinding.ItemSettingBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * 家长控制：电视上直接改设置。
 *
 * 改的是 NAS 上那份 tv.json（唯一事实来源），所以和网页编辑器永远一致，
 * 也不会出现「电视上改完、下次同步又被清单覆盖」的怪事。
 * 写盘走 PlaylistWriter：读最新 → 只改对应字段 → 备份 → 覆盖写 → 回读校验。
 *
 * 进入前主界面已经校验过 PIN；这里不再重复问，但所有改动都只在家长手里发生。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var adapter: SettingsAdapter
    private var busy = false
    private var lastFocusPos = -1

    private val footer: String by lazy {
        getString(
            R.string.set_footer,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TIME
        )
    }

    /**
     * 「未同步的修改」重放成功后，[PlaylistWriter] 会在后台异步跑一次同步，
     * 比弹窗的 onResolved() 晚一点才把新设置落到本地。只跟 settings 相关的同步
     * 补一次渲染，免得家长刚点完「覆盖 NAS」还看到上一个开关状态。
     */
    private val syncListener = PlaylistSync.Listener { _, reason ->
        if (reason.startsWith("settings") && !isFinishing && !isDestroyed) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Extras.init(this)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = SettingsAdapter()
        // 两列排布：电视上 11 项排下来不用滚太多；分组标题占满整行
        val lm = GridLayoutManager(this, 2)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.getItemViewType(position) == TYPE_GROUP) 2 else 1
        }
        b.rvSettings.layoutManager = lm
        b.rvSettings.itemAnimator = null
        b.rvSettings.adapter = adapter

        b.tvBusy.text = footer
        render()
    }

    override fun onStart() {
        super.onStart()
        PlaylistSync.register(syncListener)
    }

    override fun onStop() {
        PlaylistSync.unregister(syncListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 从播放页/锁屏回来、或同步刚写完新值，都重新读一遍
        render()
        // 家长进这一页就是要处理设置：把还没同步的修改直接摆到他面前
        b.root.post { ParentTools.showUnsyncedConflict(this) { render() } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KioskKeys.systemBlocked(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- 列表 ----------------

    private sealed class Entry {
        data class Group(val title: String) : Entry()
        data class Row(
            val title: String,
            val hint: String,
            val value: String,
            val click: () -> Unit
        ) : Entry()
    }

    private fun render() {
        val focusPos = when {
            b.rvSettings.findFocus() != null ->
                b.rvSettings.getChildAdapterPosition(b.rvSettings.findFocus())
            else -> lastFocusPos
        }

        val sch = Extras.schedule()
        val st = GateKeeper.status()
        val apps = Extras.apps()

        b.tvToday.text = if (st.limitMinutes > 0)
            getString(R.string.set_time_of_limit, st.usedMinutes, st.limitMinutes)
        else getString(R.string.set_time_today, st.usedMinutes)
        val nas = Store.nas()
        b.tvNas.text = nas?.host ?: getString(R.string.set_nas_unset)

        // OPT-07：写回失败不是「弹个 Toast 就过去」，错误条一直挂到家长处理完
        if (Store.hasUnsyncedEdit) {
            b.tvError.visibility = View.VISIBLE
            b.tvError.text = getString(
                R.string.set_error_write_failed,
                Store.unsyncedLabel,
                Store.unsyncedMessage.ifBlank { getString(R.string.word_write_failed) }
            )
        } else {
            b.tvError.visibility = View.GONE
        }

        val list = ArrayList<Entry>()
        // OPT-08：周报是家长进这一页最想先看的东西，放在最上面。
        // 走 features.report 开关（默认开）：这一页要是出问题，改一行 JSON 就能收起来。
        if (Extras.feature("report")) {
            list.add(Entry.Group(getString(R.string.set_group_overview)))
            list.add(
                Entry.Row(
                    getString(R.string.set_report),
                    getString(R.string.set_report_desc),
                    getString(R.string.act_open)
                ) { openReport() }
            )
        }
        if (Store.hasUnsyncedEdit) {
            list.add(Entry.Group(getString(R.string.set_group_unsynced)))
            list.add(
                Entry.Row(
                    getString(R.string.set_retry_save),
                    getString(R.string.set_retry_label, Store.unsyncedLabel),
                    getString(R.string.act_retry)
                ) { ParentTools.showUnsyncedConflict(this, force = true) { render() } }
            )
        }
        list.add(Entry.Group(getString(R.string.set_group_time)))
        list.add(
            Entry.Row(
                getString(R.string.set_limit),
                if (st.limitMinutes == 0) getString(R.string.set_limit_hint_unlimited)
                else if (st.bonusMinutes > 0) getString(R.string.set_limit_hint_bonus, st.bonusMinutes)
                else getString(R.string.set_limit_hint_full),
                if ((sch?.dailyLimitMinutes ?: 0) > 0) getString(R.string.set_minutes, sch!!.dailyLimitMinutes)
                else getString(R.string.set_unlimited)
            ) { chooseLimit() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_window_weekday),
                getString(R.string.set_window_weekday_hint),
                windowsText(sch?.weekday ?: emptyList())
            ) { chooseWindows(weekend = false) }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_window_weekend),
                getString(R.string.set_window_weekend_hint),
                windowsText(sch?.weekend ?: emptyList())
            ) { chooseWindows(weekend = true) }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_window_enable),
                if (sch?.enabled == true) getString(R.string.set_window_enable_on)
                else getString(R.string.set_window_enable_off),
                if (sch?.enabled == true) getString(R.string.common_on) else getString(R.string.common_off)
            ) { toggleSchedule() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_reward),
                if ((sch?.rewardMinutes ?: 0) > 0) getString(R.string.set_reward_hint_on, sch!!.rewardMinutes)
                else getString(R.string.set_reward_hint_off),
                if ((sch?.rewardMinutes ?: 0) > 0) getString(R.string.set_reward_value, sch!!.rewardMinutes)
                else getString(R.string.common_off)
            ) { chooseReward() }
        )

        list.add(Entry.Group(getString(R.string.set_group_rules)))
        list.add(
            Entry.Row(getString(R.string.set_pin), getString(R.string.set_pin_hint), getString(R.string.act_edit)) { askPin() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_pass_ratio),
                getString(R.string.set_pass_ratio_hint),
                "${(Extras.passRatio() * 100).toInt()}%"
            ) { choosePassRatio() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_apps),
                if (apps.enabled) getString(R.string.set_apps_hint_on) else getString(R.string.set_apps_hint_off),
                if (apps.enabled) getString(R.string.set_apps_count, Extras.visibleApps(apps).size)
                else getString(R.string.set_not_enabled)
            ) { editApps() }
        )

        list.add(Entry.Group(getString(R.string.set_group_maint)))
        list.add(
            Entry.Row(
                getString(R.string.set_temp_release),
                if (Extras.tempRemainingMs() > 0L)
                    getString(R.string.set_temp_hint_active) else getString(R.string.set_temp_hint_idle),
                tempText()
            ) { chooseTemp() }
        )
        list.add(
            Entry.Row(getString(R.string.set_reset_today), getString(R.string.set_reset_today_hint), "") {
                confirm(getString(R.string.set_reset_today_confirm), getString(R.string.set_reset_today_msg)) {
                    Store.clearTodayRecursive()
                    Toast.makeText(this, getString(R.string.set_reset_today_done), Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        )
        list.add(
            Entry.Row(getString(R.string.set_reset_progress), getString(R.string.set_reset_progress_hint), "") {
                confirm(getString(R.string.set_reset_progress_confirm), getString(R.string.set_reset_progress_msg)) {
                    Store.resetAllProgress()
                    com.family.curated.data.Covers.clear()
                    // 清完立刻把「空的」状态写回 NAS：否则下次恢复记录时，
                    // 备份里那些旧进度会被重新合回来，家长的操作等于没做（OPT-09）
                    StateBackup.exportAsync("reset") { }
                    Toast.makeText(this, getString(R.string.set_reset_progress_done), Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_backup),
                getString(R.string.set_backup_summary, StateBackup.lastSavedText(), Extras.stamps().size),
                getString(R.string.act_backup)
            ) { showStateBackup() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_diag),
                Diag.statusText(),
                if (Diag.pending() > 0) getString(R.string.act_upload) else getString(R.string.act_view)
            ) { showDiag() }
        )
        list.add(
            Entry.Row(getString(R.string.set_reload), getString(R.string.set_reload_hint), "") { reloadPlaylist() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_exit_launcher),
                if (Extras.kioskExitActive()) getString(R.string.set_exit_hint_lock) else getString(R.string.set_exit_hint_free),
                ""
            ) {
                if (Extras.kioskExitActive()) {
                    Extras.clearKioskExit()
                    Toast.makeText(this, getString(R.string.set_kiosk_restored), Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    ParentTools.exitToLauncher(this)
                }
            }
        )

        list.add(Entry.Group(getString(R.string.set_group_system)))
        list.add(
            Entry.Row(
                getString(R.string.set_pullback),
                if (Extras.feature("kioskPullback")) getString(R.string.set_pullback_on)
                else getString(R.string.set_pullback_off),
                if (Extras.feature("kioskPullback")) getString(R.string.common_on) else getString(R.string.common_off)
            ) { togglePullback() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_extwatch),
                if (Extras.feature("extWatchMs")) ExtWatchTracker.modeText(this)
                else getString(R.string.set_extwatch_off),
                if (Extras.feature("extWatchMs")) getString(R.string.common_on) else getString(R.string.common_off)
            ) { toggleExtWatch() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_notice),
                if (Extras.feature("remainingNotice")) getString(R.string.set_notice_on)
                else getString(R.string.set_notice_off),
                if (Extras.feature("remainingNotice")) getString(R.string.common_on) else getString(R.string.common_off)
            ) { toggleRemainingNotice() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_remote),
                if (Extras.feature("remoteControl")) getString(R.string.set_remote_on)
                else getString(R.string.set_remote_off),
                if (Extras.feature("remoteControl")) getString(R.string.common_on) else getString(R.string.common_off)
            ) { toggleRemoteControl() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_continue),
                if (Extras.feature("continueWatching")) getString(R.string.set_continue_on)
                else getString(R.string.set_continue_off),
                if (Extras.feature("continueWatching")) getString(R.string.common_on) else getString(R.string.common_off)
            ) { toggleContinueWatching() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_autonext),
                if (Extras.feature("autoNext")) getString(R.string.set_autonext_on)
                else getString(R.string.set_autonext_off),
                if (Extras.feature("autoNext")) getString(R.string.common_on) else getString(R.string.common_off)
            ) { toggleAutoNext() }
        )
        list.add(
            Entry.Row(
                getString(R.string.set_report),
                if (Extras.feature("report")) getString(R.string.set_report_on)
                else getString(R.string.set_report_off),
                if (Extras.feature("report")) getString(R.string.common_on) else getString(R.string.common_off)
            ) { toggleReport() }
        )
        val anomalies = ClockGuard.anomalyCount()
        if (anomalies > 0 || ClockGuard.frozen()) {
            list.add(
                Entry.Row(
                    getString(R.string.set_clock),
                    getString(R.string.set_clock_anomaly, anomalies),
                    if (ClockGuard.frozen()) getString(R.string.set_clock_calibrating) else getString(R.string.act_calibrate)
                ) {
                    confirm(
                        getString(R.string.set_clock_confirm),
                        getString(R.string.set_clock_confirm_msg)
                    ) {
                        ClockGuard.reset()
                        render()
                    }
                }
            )
        }
        list.add(
            Entry.Row(
                getString(R.string.set_version_row),
                getString(R.string.set_built_at, BuildConfig.BUILD_TIME),
                "v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
            ) { showAbout() }
        )

        adapter.submit(list)
        // notifyDataSetChanged 之后布局还没跑完，第一次 post 常常找不到 ViewHolder：
        // 找不到就再来一帧，避免"进了设置页方向键没反应"
        val target = if (focusPos in 0 until adapter.itemCount) focusPos else adapter.indexOfFirstRow()
        b.rvSettings.post {
            if (!focusAt(target)) b.rvSettings.post { focusAt(target) }
        }
    }

    private fun focusAt(pos: Int): Boolean {
        var i = pos
        // 分组标题不可聚焦：往下找，找不到再往上找
        while (i < adapter.itemCount) {
            val vh = b.rvSettings.findViewHolderForAdapterPosition(i)
            if (vh is SettingsAdapter.RowVH && vh.itemView.requestFocus()) {
                lastFocusPos = i
                return true
            }
            i++
        }
        i = pos - 1
        while (i >= 0) {
            val vh = b.rvSettings.findViewHolderForAdapterPosition(i)
            if (vh is SettingsAdapter.RowVH && vh.itemView.requestFocus()) {
                lastFocusPos = i
                return true
            }
            i--
        }
        i = 0
        while (i < adapter.itemCount) {
            val vh = b.rvSettings.findViewHolderForAdapterPosition(i)
            if (vh is SettingsAdapter.RowVH && vh.itemView.requestFocus()) {
                lastFocusPos = i
                return true
            }
            i++
        }
        return false
    }

    // ---------------- 文案 ----------------

    private fun windowsText(w: List<TimeWindow>): String {
        if (w.isEmpty()) return getString(R.string.set_window_all_day)
        return w.joinToString("、") { "${hhmm(it.startMin)}-${hhmm(it.endMin)}" }
    }

    private fun tempText(): String {
        val left = Extras.tempRemainingMs()
        if (left <= 0L) return getString(R.string.set_not_released)
        return getString(R.string.set_temp_left, (left / 60_000L) + 1)
    }

    private fun hhmm(min: Int) = String.format("%02d:%02d", min / 60, min % 60)

    private fun openReport() {
        startActivity(android.content.Intent(this, ReportActivity::class.java))
    }

    // ---------------- 写回 NAS ----------------

    /**
     * 改 tv.json 里的字段。
     *
     * OPT-07：只有写成功才更新本机；失败时停留在原页、挂红色错误条，
     * 并把「本想写入的内容」存下来供重试。绝不出现「界面改好了、下次同步悄悄变回去」。
     */
    private fun save(apply: (JSONObject) -> Unit, after: () -> Unit, label: String? = null) {
        if (busy) {
            Toast.makeText(this, getString(R.string.set_busy_other), Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        b.tvBusy.text = getString(R.string.set_saving)
        Thread {
            val r = PlaylistWriter.patch(apply)
            runOnUiThread {
                busy = false
                b.tvBusy.text = footer
                if (r.ok) {
                    val hadPending = Store.hasUnsyncedEdit
                    Store.clearUnsynced()
                    after()
                    Toast.makeText(
                        this,
                        if (hadPending) getString(R.string.set_saved_warn) else getString(R.string.set_saved),
                        if (hadPending) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // 不调 after()：本机保持 NAS 上的旧值
                    Store.markUnsynced(
                        label ?: getString(R.string.set_label_default),
                        r.pendingText, r.baseHash, r.message
                    )
                    Toast.makeText(this, r.message.ifBlank { getString(R.string.set_save_failed) }, Toast.LENGTH_LONG).show()
                }
                render()
            }
        }.start()
    }

    /** 时段相关的字段都在 schedule 段里；没有就建一个 */
    private fun updateSchedule(label: String, transform: (Schedule) -> Schedule) {
        val cur = Extras.schedule() ?: Schedule(enabled = false, pin = Extras.pin())
        val next = transform(cur)
        save({ root ->
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

    // ---------------- 各设置项 ----------------

    private fun chooseLimit() {
        val cur = Extras.schedule()?.dailyLimitMinutes ?: 0
        // 清单里可能是 50 这种不在预设里的值：并进来，否则选项里看不到当前值、也打不了勾
        val values = (listOf(0, 20, 30, 45, 60, 90, 120) + cur).distinct().sorted()
        val labels = values.map {
            if (it == 0) getString(R.string.set_unlimited) else getString(R.string.set_minutes, it)
        } + getString(R.string.set_custom)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_limit))
            .setItems(
                labels.mapIndexed { i, s -> (if (i < values.size && values[i] == cur) "✓ " else "   ") + s }
                    .toTypedArray()
            ) { _, which ->
                if (which == values.size) askCustomLimit(cur) else {
                    val v = values[which]
                    updateSchedule(getString(R.string.set_limit)) { it.copy(dailyLimitMinutes = v, enabled = true) }
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    /**
     * 完成必看自动加时（OPT-10）。写进 schedule.rewardMinutes，0 = 关闭。
     * 注意它加的是「今天的上限」，不是临时放行窗口：做完任务先去吃饭，
     * 晚上回来这 N 分钟还在。家长手动放行（上面的「临时放行」）是另一套计数，互不覆盖。
     */
    private fun chooseReward() {
        val cur = Extras.schedule()?.rewardMinutes ?: 0
        val values = (listOf(0, 5, 10, 15, 20, 30) + cur).distinct().sorted()
        val labels = values.map {
            if (it == 0) getString(R.string.common_off) else getString(R.string.set_reward_pick, it)
        } + getString(R.string.set_custom)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_reward_title))
            .setItems(
                labels.mapIndexed { i, s -> (if (i < values.size && values[i] == cur) "✓ " else "   ") + s }
                    .toTypedArray()
            ) { _, which ->
                if (which == values.size) askCustomReward(cur)
                else updateSchedule(getString(R.string.set_reward)) { it.copy(rewardMinutes = values[which]) }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun askCustomReward(cur: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.set_reward_input_hint, RewardMath.MAX_REWARD_MINUTES)
            if (cur > 0) setText(cur.toString())
            setSelection(text.length)
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_reward_ask_title))
            .setView(input)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v < 0 || v > RewardMath.MAX_REWARD_MINUTES) {
                    Toast.makeText(
                        this,
                        getString(R.string.set_reward_invalid, RewardMath.MAX_REWARD_MINUTES),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                updateSchedule(getString(R.string.set_reward)) { it.copy(rewardMinutes = v) }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()
        dialog.show()
        ParentTools.submitOnEnter(input, dialog)
    }

    private fun askCustomLimit(cur: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.set_limit_input_hint)
            if (cur > 0) setText(cur.toString())
            setSelection(text.length)
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_limit_ask_title))
            .setView(input)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v < 0 || v > 720) {
                    Toast.makeText(this, getString(R.string.set_limit_invalid), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                updateSchedule(getString(R.string.set_limit)) { it.copy(dailyLimitMinutes = v, enabled = true) }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()
        dialog.show()
        ParentTools.submitOnEnter(input, dialog)
    }

    private fun chooseWindows(weekend: Boolean) {
        val presets = listOf(
            getString(R.string.set_win_all_free) to emptyList<TimeWindow>(),
            getString(R.string.set_win_morning) to listOf(TimeWindow(7 * 60, 8 * 60)),
            getString(R.string.set_win_after_school) to listOf(TimeWindow(16 * 60, 19 * 60)),
            getString(R.string.set_win_afternoon) to listOf(TimeWindow(17 * 60, 20 * 60)),
            getString(R.string.set_win_evening) to listOf(TimeWindow(18 * 60, 20 * 60)),
            getString(R.string.set_win_before_bed) to listOf(TimeWindow(19 * 60, 21 * 60)),
            getString(R.string.set_win_split) to listOf(
                TimeWindow(9 * 60, 11 * 60 + 30),
                TimeWindow(16 * 60, 21 * 60)
            )
        )
        val cur = windowsText(currentWindows(weekend))
        val labels = presets.map { if (it.first == cur) "✓ ${it.first}" else "   ${it.first}" } +
            getString(R.string.set_custom_indent)
        val dayName = if (weekend) getString(R.string.set_days_weekend) else getString(R.string.set_days_weekday)
        // 当前值不在预设里时（例如 17:30-20:30），标题里也写一遍，家长知道现在是什么
        val title =
            if (cur == getString(R.string.set_window_all_day) || presets.any { it.first == cur })
                getString(R.string.set_win_title, dayName)
            else getString(R.string.set_win_title_now, dayName, cur)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == presets.size) askCustomWindows(weekend)
                else setWindows(weekend, presets[which].second)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun currentWindows(weekend: Boolean): List<TimeWindow> {
        val s = Extras.schedule() ?: return emptyList()
        return if (weekend) s.weekend else s.weekday
    }

    private fun askCustomWindows(weekend: Boolean) {
        val initial = currentWindows(weekend)
            .joinToString(",") { compactHm(it.startMin) + "-" + compactHm(it.endMin) }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.set_win_input_hint)
            setText(initial)
            setSelection(text.length)
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(if (weekend) getString(R.string.set_win_custom_weekend) else getString(R.string.set_win_custom_weekday))
            .setView(input)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val parsed = parseWindows(input.text.toString())
                if (parsed == null) {
                    Toast.makeText(this, getString(R.string.set_win_invalid), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                setWindows(weekend, parsed)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()
        dialog.show()
        ParentTools.submitOnEnter(input, dialog)
    }

    /** 只改平日或只改周末，另一天原样保留（原来的周末可能是两段，别一次改动全抹掉） */
    private fun setWindows(weekend: Boolean, w: List<TimeWindow>) {
        val cur = Extras.schedule() ?: Schedule(enabled = false, pin = Extras.pin())
        val next = if (weekend) cur.copy(weekend = w, enabled = true)
        else cur.copy(weekday = w, enabled = true)
        save({ root ->
            val o = root.optJSONObject("schedule") ?: JSONObject().also { root.put("schedule", it) }
            putWindows(o, "weekday", next.weekday)
            putWindows(o, "weekend", next.weekend)
            o.put("enabled", true)
        }, { Extras.saveSchedule(next) },
            if (weekend) getString(R.string.set_window_weekend) else getString(R.string.set_window_weekday)
        )
    }

    private fun putWindows(o: JSONObject, key: String, w: List<TimeWindow>) {
        o.put(key, JSONArray().also { a ->
            w.forEach { a.put("${hhmm(it.startMin)}-${hhmm(it.endMin)}") }
        })
    }

    private fun toggleSchedule() {
        val sch = Extras.schedule() ?: Schedule(pin = Extras.pin())
        val next = !sch.enabled
        updateSchedule(getString(R.string.set_window_enable)) { it.copy(enabled = next) }
        if (next && sch.weekday.isEmpty() && sch.dailyLimitMinutes == 0) {
            Toast.makeText(this, getString(R.string.set_hint_no_limits), Toast.LENGTH_LONG).show()
        }
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.set_pin_input_hint)
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_pin_title))
            .setView(input)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                if (v.length < 4 || v.length > 8 || !v.all { it.isDigit() }) {
                    Toast.makeText(this, getString(R.string.set_pin_invalid), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                save({ root ->
                    root.put("pin", v)
                    root.optJSONObject("schedule")?.put("pin", v)
                }, { Extras.savePin(v) }, getString(R.string.set_pin))
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()
        dialog.show()
        ParentTools.submitOnEnter(input, dialog)
    }

    private fun choosePassRatio() {
        // 用 Double 而不是 Float：Float 0.8f 转成 Double 会写成 0.800000011920929
        val values = listOf(0.80, 0.85, 0.90, 0.92, 0.95, 1.0)
        val labels = values.map { "${(it * 100).toInt()}%" }
        val cur = Math.round(Extras.passRatio() * 100)
        val idx = values.indexOfFirst { (it * 100).toInt() == cur }
        choose(getString(R.string.set_pass_ratio), labels, idx) { i ->
            val v = values[i]
            save({ root -> root.put("passRatio", v) },
                { Extras.savePassRatio(v.toFloat()) }, getString(R.string.set_pass_ratio))
        }
    }

    private fun editApps() {
        val cfg: AppsConfig = Extras.apps()
        if (cfg.enabled) {
            ParentTools.pickApps(this) { render() }
            return
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_apps_confirm))
            .setMessage(getString(R.string.set_apps_confirm_msg, cfg.title))
            .setPositiveButton(getString(R.string.common_on)) { _, _ ->
                save({ root ->
                    val o = root.optJSONObject("apps") ?: JSONObject().also { root.put("apps", it) }
                    o.put("enabled", true)
                }, {
                    Extras.saveApps(cfg.copy(enabled = true))
                    ParentTools.pickApps(this) { render() }
                }, getString(R.string.set_apps))
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun chooseTemp() {
        val active = Extras.tempRemainingMs() > 0L
        val labels = (if (active) arrayListOf(getString(R.string.set_temp_end)) else arrayListOf()) +
            listOf(
                getString(R.string.temp_15), getString(R.string.temp_30),
                getString(R.string.temp_60), getString(R.string.temp_120)
            )
        val minutes = listOf(15, 30, 60, 120)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_temp_release))
            .setItems(labels.toTypedArray()) { _, which ->
                if (active && which == 0) {
                    Extras.clearTemp()
                    Toast.makeText(this, getString(R.string.set_temp_restored), Toast.LENGTH_SHORT).show()
                } else {
                    val idx = if (active) which - 1 else which
                    val m = minutes.getOrNull(idx) ?: return@setItems
                    Extras.grantTemp(m)
                    Toast.makeText(this, getString(R.string.set_temp_granted, m), Toast.LENGTH_SHORT).show()
                }
                render()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    /**
     * 功能开关统一走这一处：写回 NAS 成功才更新电视本机。
     * 打开远程控制时顺手 nudge 一次，不用等下一个 15 秒轮询。
     */
    private fun toggleFeature(name: String, label: String) {
        val next = !Extras.feature(name)
        save({ root ->
            val o = root.optJSONObject("features") ?: JSONObject().also { root.put("features", it) }
            o.put(name, next)
        }, {
            Extras.saveFeatures(Features(Extras.features().on + (name to next)))
            if (name == "remoteControl" && next) RemoteControl.nudge()
        }, label)
    }

    /** 后台拉回是「老行为」，默认开；个别电视上会和投屏/系统弹窗打架，可以在这里关掉 */
    private fun togglePullback() = toggleFeature("kioskPullback", getString(R.string.set_pullback))

    /**
     * 外部应用计时默认开：2026-11-10 实机确认孩子从应用入口进优酷 2 小时不入账，
     * 等于绕过每日上限。这是修 bug 不是灰度，装上就生效；出问题在这里关掉，15 秒回滚。
     */
    private fun toggleExtWatch() = toggleFeature("extWatchMs", getString(R.string.set_extwatch))

    private fun toggleRemainingNotice() = toggleFeature("remainingNotice", getString(R.string.set_notice))

    private fun toggleRemoteControl() = toggleFeature("remoteControl", getString(R.string.set_remote))

    private fun toggleContinueWatching() = toggleFeature("continueWatching", getString(R.string.set_continue))

    /** 默认关：连播会让时长消耗明显变快，家长要自己决定要不要打开 */
    private fun toggleAutoNext() = toggleFeature("autoNext", getString(R.string.set_autonext))

    /** 周报只读、不改变任何行为，默认开；万一某个电视上渲染出问题，这里一键收起入口 */
    private fun toggleReport() = toggleFeature("report", getString(R.string.set_report))

    /** 排障用：版本、构建时间、功能开关现状一次看全，不用连电脑 */
    private fun showAbout() {
        val names = linkedMapOf(
            "kioskPullback" to getString(R.string.set_pullback),
            "extWatchMs" to getString(R.string.set_extwatch),
            "remainingNotice" to getString(R.string.set_notice),
            "remoteControl" to getString(R.string.set_remote),
            "continueWatching" to getString(R.string.set_continue),
            "autoNext" to getString(R.string.set_autonext),
            "report" to getString(R.string.set_report)
        )
        val switches = names.entries.joinToString("\n") { (k, label) ->
            "$label：${if (Extras.feature(k)) "开" else "关"}"
        }
        val anomalies = ClockGuard.anomalyCount()
        val msg = buildString {
            append(getString(R.string.set_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            append(getString(R.string.set_about_built, BuildConfig.BUILD_TIME))
            append(getString(R.string.set_about_pkg, packageName))
            append(getString(R.string.set_about_features))
            append(switches)
            append(getString(R.string.set_about_ext, Extras.extMsToday() / 60_000L))
            append(getString(R.string.set_about_mode, ExtWatchTracker.modeText(this@SettingsActivity)))
            append(getString(R.string.set_about_remote, Store.playlistShare, RemoteControl.controlPath()))
            append(
                getString(R.string.set_about_last_cmd, Store.lastControlAt.ifBlank { getString(R.string.word_none) })
            )
            if (Extras.remoteLockActive()) append(
                getString(R.string.set_about_remote_lock, Extras.remoteLockRemainingMs() / 60_000L + 1)
            )
            if (anomalies > 0) {
                append(getString(R.string.set_about_anomaly, anomalies))
                if (ClockGuard.frozen()) append(getString(R.string.set_about_frozen))
            }
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_about_title))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.common_got_it), null)
            .show()
    }

    private fun reloadPlaylist() {
        if (busy) return
        busy = true
        b.tvBusy.text = getString(R.string.set_reloading)
        Thread {
            Smb.reset()
            val res = Repo.load()
            val ok = res.error == null
            PlaylistSync.applyToLocal(res.playlist)
            runOnUiThread {
                busy = false
                b.tvBusy.text = footer
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.set_reloaded) else getString(R.string.set_reload_failed),
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        }.start()
    }

    // ---------------- 对话框小工具 ----------------

    private fun choose(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        val shown = labels.mapIndexed { i, s -> (if (i == current) "✓ " else "   ") + s }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setItems(shown.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    /**
     * 记录备份（OPT-09）：打卡与播放进度存在 NAS 的 `state.json`。
     * 平时由 App 自己备份（退到后台 / 每 5 分钟 / 每日首次打卡），这里只给家长
     * 一个「现在就备份一次」和「从 NAS 恢复」的入口。
     */
    private fun showStateBackup() {
        val days = Extras.stamps().size
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_state_title, days))
            .setMessage(
                getString(
                    R.string.set_state_msg,
                    StateBackup.lastSavedText(),
                    StateBackup.statePath()
                )
            )
            .setItems(arrayOf(getString(R.string.set_state_do_backup), getString(R.string.set_state_do_restore))) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(this, getString(R.string.set_state_backing_up), Toast.LENGTH_SHORT).show()
                        StateBackup.exportAsync("manual") { r ->
                            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                            if (!isFinishing) render()
                        }
                    }
                    else -> confirmRestore()
                }
            }
            .setNegativeButton(getString(R.string.common_close), null)
            .show()
    }

    /**
     * 诊断日志（OPT-17）：出问题时电视把线索写在这里，家长不用连电脑。
     *
     * 只显示本地最近几条（够判断「是不是一直在报同一个错」），
     * 归档在 NAS 上，需要看全的可以去电脑上开 `data:curated/logs/`。
     */
    private fun showDiag() {
        val msg = buildString {
            append(getString(R.string.set_diag_intro))
            append(getString(R.string.set_diag_pending, Diag.pending()))
            append(getString(R.string.set_diag_archive, Diag.logDirPath()))
            append(getString(R.string.set_diag_keep))
            append(getString(R.string.set_diag_recent)).append(Diag.recentText())
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(getString(R.string.set_diag))
            .setMessage(msg)
            .setItems(arrayOf(getString(R.string.set_diag_upload_now), getString(R.string.set_diag_clear))) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(this, getString(R.string.set_diag_uploading), Toast.LENGTH_SHORT).show()
                        Diag.uploadAsync { r ->
                            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                            if (!isFinishing) render()
                        }
                    }
                    else -> confirm(getString(R.string.set_diag_clear_confirm), getString(R.string.set_diag_clear_msg)) {
                        Diag.clear()
                        Toast.makeText(this, getString(R.string.set_diag_cleared), Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }
            .setNegativeButton(getString(R.string.common_close), null)
            .show()
    }

    private fun confirmRestore() {
        confirm(
            getString(R.string.set_restore_confirm),
            getString(R.string.set_restore_msg)
        ) {
            Toast.makeText(this, getString(R.string.set_restoring), Toast.LENGTH_SHORT).show()
            StateBackup.importAsync { r ->
                Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                if (!isFinishing) render()
            }
        }
    }

    private fun confirm(title: String, message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.common_confirm)) { _, _ -> onOk() }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun compactHm(min: Int) = String.format("%02d%02d", min / 60, min % 60)

    /** "1800-2000" / "18:00-20:00" / "18:00-20:00,21:00-21:30" 都认；空 = 全天 */
    private fun parseWindows(text: String): List<TimeWindow>? {
        val t = text.trim()
        if (t.isEmpty() || t == getString(R.string.set_window_all_day) ||
            t == getString(R.string.set_win_all_free)
        ) return emptyList()
        val re = Regex("(\\d{1,2})[:：]?(\\d{2})\\s*[-~至–—]\\s*(\\d{1,2})[:：]?(\\d{2})")
        val out = re.findAll(t).mapNotNull { m ->
            try {
                val s = m.groupValues[1].toInt().coerceIn(0, 23) * 60 +
                    m.groupValues[2].toInt().coerceIn(0, 59)
                val e = m.groupValues[3].toInt().coerceIn(0, 23) * 60 +
                    m.groupValues[4].toInt().coerceIn(0, 59)
                TimeWindow(s, e)
            } catch (e: Exception) {
                null
            }
        }.toList()
        return if (out.isEmpty()) null else out
    }

    // ---------------- 列表适配器 ----------------

    private inner class SettingsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = ArrayList<Entry>()

        fun submit(list: List<Entry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun indexOfFirstRow(): Int {
            for (i in items.indices) if (items[i] is Entry.Row) return i
            return 0
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) =
            if (items[position] is Entry.Group) TYPE_GROUP else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = android.view.LayoutInflater.from(parent.context)
            return if (viewType == TYPE_GROUP) {
                GroupVH(inflater.inflate(R.layout.item_setting_group, parent, false))
            } else {
                RowVH(ItemSettingBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val e = items[position]) {
                is Entry.Group -> (holder as GroupVH).set(e.title)
                is Entry.Row -> (holder as RowVH).set(e)
            }
        }

        inner class GroupVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tv: TextView = v as TextView
            fun set(title: String) {
                tv.text = title
            }
        }

        inner class RowVH(val bind: ItemSettingBinding) : RecyclerView.ViewHolder(bind.root) {
            fun set(e: Entry.Row) {
                bind.tvTitle.text = e.title
                bind.tvValue.text = e.value
                if (e.hint.isBlank()) {
                    bind.tvHint.visibility = View.GONE
                } else {
                    bind.tvHint.text = e.hint
                    bind.tvHint.visibility = View.VISIBLE
                }
                bind.tvArrow.visibility = if (e.value.endsWith("›")) View.GONE else View.VISIBLE
                bind.row.setOnClickListener { e.click() }
            }
        }
    }

    private companion object {
        const val TYPE_GROUP = 0
        const val TYPE_ROW = 1
    }
}
