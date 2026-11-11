package com.family.curated

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.data.KioskKeys
import com.family.curated.data.PlaylistSync
import com.family.curated.data.PlaylistWriter
import com.family.curated.data.Store
import com.family.curated.databinding.ActivitySettingsBinding
import com.family.curated.settings.Entry
import com.family.curated.settings.MaintGroup
import com.family.curated.settings.OverviewGroup
import com.family.curated.settings.RulesGroup
import com.family.curated.settings.SettingsAdapter
import com.family.curated.settings.SettingsGroup
import com.family.curated.settings.SettingsHost
import com.family.curated.settings.SystemGroup
import com.family.curated.settings.TimeGroup
import org.json.JSONObject

/**
 * 家长控制：电视上直接改设置。
 *
 * 改的是 NAS 上那份 tv.json（唯一事实来源），所以和网页编辑器永远一致，
 * 也不会出现「电视上改完、下次同步又被清单覆盖」的怪事。
 * 写盘走 [PlaylistWriter]：读最新 → 只改对应字段 → 备份 → 覆盖写 → 回读校验。
 *
 * 这一页只留「外壳」：布局、列表、写回、焦点保持。
 * 具体每一行是什么、点了弹什么框，按分组放在 `settings/` 下：
 * [OverviewGroup]（周报 / 未同步重试）、[TimeGroup]（上限 / 时段 / 奖励）、
 * [RulesGroup]（PIN / 通过率 / 应用）、[MaintGroup]（放行 / 清记录 / 备份 / 日志 / 重载）、
 * [SystemGroup]（功能开关 / 时钟 / 版本）。
 * 加一个设置项就动对应分组的 `rows()`，不必再打开这个文件。
 *
 * 进入前主界面已经校验过 PIN；这里不再重复问，但所有改动都只在家长手里发生。
 */
class SettingsActivity : AppCompatActivity(), SettingsHost {

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

    /** 分组顺序 = 屏幕上从上到下的顺序 */
    private val groups: List<SettingsGroup> by lazy {
        listOf(
            OverviewGroup(this),
            TimeGroup(this),
            RulesGroup(this),
            MaintGroup(this),
            SystemGroup(this)
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
        // 两列排布：电视上十几项排下来不用滚太多；分组标题占满整行
        val lm = GridLayoutManager(this, 2)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.getItemViewType(position) == SettingsAdapter.TYPE_GROUP) 2 else 1
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

    // ---------------- 页面骨架 ----------------

    private fun render() {
        val focusPos = when {
            b.rvSettings.findFocus() != null ->
                b.rvSettings.getChildAdapterPosition(b.rvSettings.findFocus())
            else -> lastFocusPos
        }

        val st = GateKeeper.status()

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
        groups.forEach { list.addAll(it.rows()) }
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

    // ---------------- SettingsHost ----------------

    override val act: AppCompatActivity get() = this

    override fun str(id: Int, vararg args: Any?): String =
        if (args.isEmpty()) getString(id) else getString(id, *args)

    override fun refresh() {
        render()
    }

    override fun beginTask(status: String): Boolean {
        if (busy) return false
        busy = true
        b.tvBusy.text = status
        return true
    }

    override fun endTask() {
        busy = false
        b.tvBusy.text = footer
    }

    override fun toast(text: String, long: Boolean) {
        Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /**
     * 改 tv.json 里的字段。
     *
     * OPT-07：只有写成功才更新本机；失败时停留在原页、挂红色错误条，
     * 并把「本想写入的内容」存下来供重试。绝不出现「界面改好了、下次同步悄悄变回去」。
     */
    override fun save(apply: (JSONObject) -> Unit, after: () -> Unit, label: String?) {
        if (!beginTask(getString(R.string.set_saving))) {
            toast(getString(R.string.set_busy_other))
            return
        }
        Thread {
            val r = PlaylistWriter.patch(apply)
            runOnUiThread {
                endTask()
                if (r.ok) {
                    val hadPending = Store.hasUnsyncedEdit
                    Store.clearUnsynced()
                    after()
                    toast(
                        if (hadPending) getString(R.string.set_saved_warn) else getString(R.string.set_saved),
                        long = hadPending
                    )
                } else {
                    // 不调 after()：本机保持 NAS 上的旧值
                    Store.markUnsynced(
                        label ?: getString(R.string.set_label_default),
                        r.pendingText, r.baseHash, r.message
                    )
                    toast(r.message.ifBlank { getString(R.string.set_save_failed) }, long = true)
                }
                render()
            }
        }.start()
    }
}
