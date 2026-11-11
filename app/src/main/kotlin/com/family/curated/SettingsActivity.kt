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
import com.family.curated.data.Extras
import com.family.curated.data.ExtWatchTracker
import com.family.curated.data.Features
import com.family.curated.data.GateKeeper
import com.family.curated.data.KioskKeys
import com.family.curated.data.PlaylistSync
import com.family.curated.data.PlaylistWriter
import com.family.curated.data.RemoteControl
import com.family.curated.data.Repo
import com.family.curated.data.Schedule
import com.family.curated.data.Smb
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

    private val footer = "方向键选择 · OK 修改 · 返回键回到片单 · v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TIME}"

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
            "今天 ${st.usedMinutes}/${st.limitMinutes} 分钟"
        else "今天 ${st.usedMinutes} 分钟"
        val nas = Store.nas()
        b.tvNas.text = nas?.host ?: "未配置 NAS"

        // OPT-07：写回失败不是「弹个 Toast 就过去」，错误条一直挂到家长处理完
        if (Store.hasUnsyncedEdit) {
            b.tvError.visibility = View.VISIBLE
            b.tvError.text = "⚠ 「${Store.unsyncedLabel}」没能写进 NAS：" +
                "${Store.unsyncedMessage.ifBlank { "写入失败" }}\n" +
                "NAS 上仍是旧设置，电视本机也没有改。点下面「未同步的修改」重试。"
        } else {
            b.tvError.visibility = View.GONE
        }

        val list = ArrayList<Entry>()
        if (Store.hasUnsyncedEdit) {
            list.add(Entry.Group("未同步的修改"))
            list.add(
                Entry.Row(
                    "重试保存到 NAS",
                    "上次改「${Store.unsyncedLabel}」失败了",
                    "重试 ›"
                ) { ParentTools.showUnsyncedConflict(this, force = true) { render() } }
            )
        }
        list.add(Entry.Group("时间与时长"))
        list.add(
            Entry.Row(
                "每日观看上限",
                if (st.limitMinutes == 0) "现在不按分钟限制" else "到量自动锁屏",
                if ((sch?.dailyLimitMinutes ?: 0) > 0) "${sch!!.dailyLimitMinutes} 分钟" else "不限制"
            ) { chooseLimit() }
        )
        list.add(
            Entry.Row(
                "平日时段",
                "留空 = 全天可看",
                windowsText(sch?.weekday ?: emptyList())
            ) { chooseWindows(weekend = false) }
        )
        list.add(
            Entry.Row(
                "周末时段",
                "可以设两段",
                windowsText(sch?.weekend ?: emptyList())
            ) { chooseWindows(weekend = true) }
        )
        list.add(
            Entry.Row(
                "时段限制",
                if (sch?.enabled == true) "按上面的时段执行" else "完全不受时段限制",
                if (sch?.enabled == true) "开启" else "关闭"
            ) { toggleSchedule() }
        )

        list.add(Entry.Group("规则"))
        list.add(
            Entry.Row("家长密码", "家长菜单要输这个", "修改 ›") { askPin() }
        )
        list.add(
            Entry.Row(
                "看完判定",
                "超过这个比例算看完",
                "${(Extras.passRatio() * 100).toInt()}%"
            ) { choosePassRatio() }
        )
        list.add(
            Entry.Row(
                "应用入口",
                if (apps.enabled) "孩子能看到的 App" else "片单里没开应用分区",
                if (apps.enabled) "${Extras.visibleApps(apps).size} 个" else "未开启"
            ) { editApps() }
        )

        list.add(Entry.Group("维护"))
        list.add(
            Entry.Row(
                "临时放行",
                if (Extras.tempRemainingMs() > 0L)
                    "放行中，到点自动恢复" else "不锁屏，到点结束",
                tempText()
            ) { chooseTemp() }
        )
        list.add(
            Entry.Row("今天全部重来", "清掉今天的完成记录", "") {
                confirm("今天全部重来？", "今天的完成记录会清空，必修要重新看一遍。") {
                    Store.clearTodayRecursive()
                    Toast.makeText(this, "已清空今天的记录", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        )
        list.add(
            Entry.Row("清空播放进度", "所有视频回到开头", "") {
                confirm("清空所有播放进度？", "所有视频会回到开头，这个操作不能撤销。") {
                    Store.resetAllProgress()
                    com.family.curated.data.Covers.clear()
                    Toast.makeText(this, "已清空全部进度", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        )
        list.add(
            Entry.Row("重新读取清单", "从 NAS 重新拉一次", "") { reloadPlaylist() }
        )
        list.add(
            Entry.Row(
                "退出到桌面",
                if (Extras.kioskExitActive()) "点这里恢复儿童锁" else "家长维护用",
                ""
            ) {
                if (Extras.kioskExitActive()) {
                    Extras.clearKioskExit()
                    Toast.makeText(this, "儿童锁已恢复", Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    ParentTools.exitToLauncher(this)
                }
            }
        )

        list.add(Entry.Group("系统"))
        list.add(
            Entry.Row(
                "后台拉回",
                if (Extras.feature("kioskPullback")) "被切走后自动拉回小剧场"
                else "已关闭：按 Home 不再拉回",
                if (Extras.feature("kioskPullback")) "开启" else "关闭"
            ) { togglePullback() }
        )
        list.add(
            Entry.Row(
                "外部应用计时",
                if (Extras.feature("extWatchMs")) ExtWatchTracker.modeText(this)
                else "已关闭：应用入口不计入上限",
                if (Extras.feature("extWatchMs")) "开启" else "关闭"
            ) { toggleExtWatch() }
        )
        list.add(
            Entry.Row(
                "剩余时间预告",
                if (Extras.feature("remainingNotice")) "播放页显示还剩几分钟，到点前提醒"
                else "已关闭：到点前不提示",
                if (Extras.feature("remainingNotice")) "开启" else "关闭"
            ) { toggleRemainingNotice() }
        )
        list.add(
            Entry.Row(
                "远程控制",
                if (Extras.feature("remoteControl")) "读 NAS 的 control.json，15 秒生效"
                else "已关闭：不读 control.json",
                if (Extras.feature("remoteControl")) "开启" else "关闭"
            ) { toggleRemoteControl() }
        )
        list.add(
            Entry.Row(
                "首页继续看",
                if (Extras.feature("continueWatching")) "顶部显示没看完的 5 条"
                else "已关闭：不显示继续看",
                if (Extras.feature("continueWatching")) "开启" else "关闭"
            ) { toggleContinueWatching() }
        )
        list.add(
            Entry.Row(
                "自动连播",
                if (Extras.feature("autoNext")) "播完 5 秒接下一集，限额到点停"
                else "已关闭：播完回剧集列表",
                if (Extras.feature("autoNext")) "开启" else "关闭"
            ) { toggleAutoNext() }
        )
        val anomalies = ClockGuard.anomalyCount()
        if (anomalies > 0 || ClockGuard.frozen()) {
            list.add(
                Entry.Row(
                    "电视时间",
                    "系统时间被改过 $anomalies 次，用量已按正确时间计算",
                    if (ClockGuard.frozen()) "正在校正" else "校准 ›"
                ) {
                    confirm(
                        "按实际时间校准？",
                        "只在电视时间确实设错过时才需要点。\n\n" +
                            "校准后，今天的已看时长不会被清零；" +
                            "「改日期绕过限额」的记录也会重新计数。"
                    ) {
                        ClockGuard.reset()
                        render()
                    }
                }
            )
        }
        list.add(
            Entry.Row(
                "版本与开关",
                "构建于 ${BuildConfig.BUILD_TIME}",
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
        if (w.isEmpty()) return "全天"
        return w.joinToString("、") { "${hhmm(it.startMin)}-${hhmm(it.endMin)}" }
    }

    private fun tempText(): String {
        val left = Extras.tempRemainingMs()
        if (left <= 0L) return "未放行"
        return "剩余 ${(left / 60_000L) + 1} 分钟"
    }

    private fun hhmm(min: Int) = String.format("%02d:%02d", min / 60, min % 60)

    // ---------------- 写回 NAS ----------------

    /**
     * 改 tv.json 里的字段。
     *
     * OPT-07：只有写成功才更新本机；失败时停留在原页、挂红色错误条，
     * 并把「本想写入的内容」存下来供重试。绝不出现「界面改好了、下次同步悄悄变回去」。
     */
    private fun save(apply: (JSONObject) -> Unit, after: () -> Unit, label: String = "设置") {
        if (busy) {
            Toast.makeText(this, "上一个修改还在保存，稍等一下", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        b.tvBusy.text = "正在保存到 NAS…"
        UiAsync.run(
            this,
            io = { PlaylistWriter.patch(apply) },
            onMain = { r ->
                busy = false
                b.tvBusy.text = footer
                if (r.ok) {
                    val hadPending = Store.hasUnsyncedEdit
                    Store.clearUnsynced()
                    after()
                    Toast.makeText(
                        this,
                        if (hadPending) "已保存（之前那次没同步的修改请重新设一次）" else "已保存",
                        if (hadPending) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // 不调 after()：本机保持 NAS 上的旧值
                    Store.markUnsynced(label, r.pendingText, r.baseHash, r.message)
                    Toast.makeText(this, r.message.ifBlank { "保存失败" }, Toast.LENGTH_LONG).show()
                }
                render()
            }
        )
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
            o.put("lockMessage", next.lockMessage)
            if (next.pin.isNotBlank()) o.put("pin", next.pin)
        }, { Extras.saveSchedule(next) }, label)
    }

    // ---------------- 各设置项 ----------------

    private fun chooseLimit() {
        val cur = Extras.schedule()?.dailyLimitMinutes ?: 0
        // 清单里可能是 50 这种不在预设里的值：并进来，否则选项里看不到当前值、也打不了勾
        val values = (listOf(0, 20, 30, 45, 60, 90, 120) + cur).distinct().sorted()
        val labels = values.map { if (it == 0) "不限制" else "$it 分钟" } + "自定义…"
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("每日观看上限")
            .setItems(
                labels.mapIndexed { i, s -> (if (i < values.size && values[i] == cur) "✓ " else "   ") + s }
                    .toTypedArray()
            ) { _, which ->
                if (which == values.size) askCustomLimit(cur) else {
                    val v = values[which]
                    updateSchedule("每日观看上限") { it.copy(dailyLimitMinutes = v, enabled = true) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askCustomLimit(cur: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "分钟数，0 = 不限制"
            if (cur > 0) setText(cur.toString())
            setSelection(text.length)
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("每天最多看多少分钟")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v < 0 || v > 720) {
                    Toast.makeText(this, "请输入 0~720 之间的分钟数", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                updateSchedule("每日观看上限") { it.copy(dailyLimitMinutes = v, enabled = true) }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        ParentTools.submitOnEnter(input, dialog)
    }

    private fun chooseWindows(weekend: Boolean) {
        val presets = listOf(
            "全天不限" to emptyList<TimeWindow>(),
            "早上 07:00-08:00" to listOf(TimeWindow(7 * 60, 8 * 60)),
            "放学后 16:00-19:00" to listOf(TimeWindow(16 * 60, 19 * 60)),
            "下午 17:00-20:00" to listOf(TimeWindow(17 * 60, 20 * 60)),
            "晚饭后 18:00-20:00" to listOf(TimeWindow(18 * 60, 20 * 60)),
            "睡前 19:00-21:00" to listOf(TimeWindow(19 * 60, 21 * 60)),
            "上午 + 下午" to listOf(
                TimeWindow(9 * 60, 11 * 60 + 30),
                TimeWindow(16 * 60, 21 * 60)
            )
        )
        val cur = windowsText(currentWindows(weekend))
        val labels = presets.map { if (it.first == cur) "✓ ${it.first}" else "   ${it.first}" } +
            "   自定义…"
        val dayName = if (weekend) "周六周日" else "周一到周五"
        // 当前值不在预设里时（例如 17:30-20:30），标题里也写一遍，家长知道现在是什么
        val title = if (cur == "全天" || presets.any { it.first == cur }) "$dayName 允许时段"
        else "$dayName 允许时段（现在 $cur）"
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == presets.size) askCustomWindows(weekend)
                else setWindows(weekend, presets[which].second)
            }
            .setNegativeButton("取消", null)
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
            hint = "例如 1800-2000，多段用逗号隔开，留空 = 全天"
            setText(initial)
            setSelection(text.length)
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(if (weekend) "自定义周末时段" else "自定义平日时段")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val parsed = parseWindows(input.text.toString())
                if (parsed == null) {
                    Toast.makeText(this, "格式看不懂，例如 1800-2000 或 18:00-20:00", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                setWindows(weekend, parsed)
            }
            .setNegativeButton("取消", null)
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
        }, { Extras.saveSchedule(next) }, if (weekend) "周末时段" else "平日时段")
    }

    private fun putWindows(o: JSONObject, key: String, w: List<TimeWindow>) {
        o.put(key, JSONArray().also { a ->
            w.forEach { a.put("${hhmm(it.startMin)}-${hhmm(it.endMin)}") }
        })
    }

    private fun toggleSchedule() {
        val sch = Extras.schedule() ?: Schedule(pin = Extras.pin())
        val next = !sch.enabled
        updateSchedule("时段限制") { it.copy(enabled = next) }
        if (next && sch.weekday.isEmpty() && sch.dailyLimitMinutes == 0) {
            Toast.makeText(this, "现在是全天不限，建议再设一下时段或上限", Toast.LENGTH_LONG).show()
        }
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4~8 位数字，留空不改"
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("修改家长密码")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                if (v.length < 4 || v.length > 8 || !v.all { it.isDigit() }) {
                    Toast.makeText(this, "请输入 4~8 位数字", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                save({ root ->
                    root.put("pin", v)
                    root.optJSONObject("schedule")?.put("pin", v)
                }, { Extras.savePin(v) }, "家长密码")
            }
            .setNegativeButton("取消", null)
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
        choose("看完判定", labels, idx) { i ->
            val v = values[i]
            save({ root -> root.put("passRatio", v) },
                { Extras.savePassRatio(v.toFloat()) }, "看完判定")
        }
    }

    private fun editApps() {
        val cfg: AppsConfig = Extras.apps()
        if (cfg.enabled) {
            ParentTools.pickApps(this) { render() }
            return
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("开启应用入口？")
            .setMessage("开启后，主界面会多出一个「${cfg.title}」分区，放家长勾选的第三方 App。")
            .setPositiveButton("开启") { _, _ ->
                save({ root ->
                    val o = root.optJSONObject("apps") ?: JSONObject().also { root.put("apps", it) }
                    o.put("enabled", true)
                }, {
                    Extras.saveApps(cfg.copy(enabled = true))
                    ParentTools.pickApps(this) { render() }
                }, "应用入口")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseTemp() {
        val active = Extras.tempRemainingMs() > 0L
        val labels = (if (active) arrayListOf("结束放行（立刻恢复限制）") else arrayListOf()) +
            listOf("放行 15 分钟", "放行 30 分钟", "放行 60 分钟", "放行 120 分钟")
        val minutes = listOf(15, 30, 60, 120)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("临时放行")
            .setItems(labels.toTypedArray()) { _, which ->
                if (active && which == 0) {
                    Extras.clearTemp()
                    Toast.makeText(this, "已恢复时间限制", Toast.LENGTH_SHORT).show()
                } else {
                    val idx = if (active) which - 1 else which
                    val m = minutes.getOrNull(idx) ?: return@setItems
                    Extras.grantTemp(m)
                    Toast.makeText(this, "已放行 $m 分钟", Toast.LENGTH_SHORT).show()
                }
                render()
            }
            .setNegativeButton("取消", null)
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
    private fun togglePullback() = toggleFeature("kioskPullback", "后台拉回")

    /**
     * 外部应用计时默认开：2026-11-10 实机确认孩子从应用入口进优酷 2 小时不入账，
     * 等于绕过每日上限。这是修 bug 不是灰度，装上就生效；出问题在这里关掉，15 秒回滚。
     */
    private fun toggleExtWatch() = toggleFeature("extWatchMs", "外部应用计时")

    private fun toggleRemainingNotice() = toggleFeature("remainingNotice", "剩余时间预告")

    private fun toggleRemoteControl() = toggleFeature("remoteControl", "远程控制")

    private fun toggleContinueWatching() = toggleFeature("continueWatching", "首页继续看")

    /** 默认关：连播会让时长消耗明显变快，家长要自己决定要不要打开 */
    private fun toggleAutoNext() = toggleFeature("autoNext", "自动连播")

    /** 排障用：版本、构建时间、功能开关现状一次看全，不用连电脑 */
    private fun showAbout() {
        val names = linkedMapOf(
            "kioskPullback" to "后台拉回",
            "extWatchMs" to "外部应用计时",
            "remainingNotice" to "剩余时间预告",
            "remoteControl" to "远程控制",
            "continueWatching" to "首页继续看",
            "autoNext" to "自动连播",
            "report" to "周报（占位）"
        )
        val switches = names.entries.joinToString("\n") { (k, label) ->
            "$label：${if (Extras.feature(k)) "开" else "关"}"
        }
        val anomalies = ClockGuard.anomalyCount()
        val msg = buildString {
            append("版本：v${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）\n")
            append("构建：${BuildConfig.BUILD_TIME}\n")
            append("包名：${packageName}\n\n")
            append("功能开关（tv.json 的 features 段，改完 15 秒生效）\n")
            append(switches)
            append("\n\n今日外部应用：${Extras.extMsToday() / 60_000L} 分钟（已计入每日上限）")
            append("\n计时模式：${ExtWatchTracker.modeText(this@SettingsActivity)}")
            append("\n远程控制卡：${Store.playlistShare}:${RemoteControl.controlPath()}")
            append("（最近指令 ${Store.lastControlAt.ifBlank { "无" }}）")
            if (Extras.remoteLockActive()) append("\n家长远程锁定中：剩余 ${Extras.remoteLockRemainingMs() / 60_000L + 1} 分钟")
            if (anomalies > 0) {
                append("\n\n系统时间被改过 $anomalies 次")
                if (ClockGuard.frozen()) append("（当前按校正时间计算）")
            }
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("关于")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun reloadPlaylist() {
        if (busy) return
        busy = true
        b.tvBusy.text = "正在重新读取清单…"
        UiAsync.run(
            this,
            io = {
                Smb.reset()
                val res = Repo.load()
                PlaylistSync.applyToLocal(res.playlist)
                res.error == null
            },
            onMain = { ok ->
                busy = false
                b.tvBusy.text = footer
                Toast.makeText(
                    this,
                    if (ok) "已重新读取清单" else "连不上 NAS，正在用上次的清单",
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        )
    }

    // ---------------- 对话框小工具 ----------------

    private fun choose(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        val shown = labels.mapIndexed { i, s -> (if (i == current) "✓ " else "   ") + s }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setItems(shown.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirm(title: String, message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun compactHm(min: Int) = String.format("%02d%02d", min / 60, min % 60)

    /** "1800-2000" / "18:00-20:00" / "18:00-20:00,21:00-21:30" 都认；空 = 全天 */
    private fun parseWindows(text: String): List<TimeWindow>? {
        val t = text.trim()
        if (t.isEmpty() || t == "全天" || t == "全天不限") return emptyList()
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
