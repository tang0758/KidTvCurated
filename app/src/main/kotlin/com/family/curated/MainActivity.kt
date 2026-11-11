package com.family.curated

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.curated.data.AppCatalog
import com.family.curated.data.AppsConfig
import com.family.curated.data.Badges
import com.family.curated.data.ContinueMath
import com.family.curated.data.Extras
import com.family.curated.data.ExtWatchTracker
import com.family.curated.data.GateKeeper
import com.family.curated.data.Item
import com.family.curated.data.KioskKeys
import com.family.curated.data.Playlist
import com.family.curated.data.PlaylistSync
import com.family.curated.data.Repo
import com.family.curated.data.Section
import com.family.curated.data.StateBackup
import com.family.curated.data.StateMerge
import com.family.curated.data.Store
import com.family.curated.databinding.ActivityMainBinding
import com.family.curated.ui.SectionsAdapter

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainActivity" }

    private lateinit var b: ActivityMainBinding
    private var playlist: Playlist? = null
    private var adapter: SectionsAdapter? = null
    private var skipGateOnce = false
    private var loading = false
    private var celebratedToday = false

    /**
     * 记住最后获得焦点的条目（分区 key + 条目 key）。
     * 从播放页/剧集页回来时 onResume 会 notifyDataSetChanged，原来那张卡的 View 被回收，
     * 焦点就会掉到「我的奖励」按钮上；有了这两个 key 就能准确复位。
     */
    private var lastFocusSection: String? = null
    private var lastFocusItem: String? = null

    /** 从「家长控制」回来时把焦点还给那个按钮，家长可能要连着调几项 */
    private var focusParentOnResume = false

    private val ui = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateHeader()
            ui.postDelayed(this, 5000)
        }
    }

    /** NAS 清单变了就无感重绘：复用 adapter，滚动位置和已解锁状态都留着 */
    private val syncListener = PlaylistSync.Listener { pl, _ ->
        if (isFinishing || isDestroyed) return@Listener
        playlist = pl
        render(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Extras.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        b.rvSections.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        b.rvSections.itemAnimator = null

        b.btnRewards.setOnClickListener { openRewards() }
        b.btnRewards.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                .setDuration(120).start()
        }

        // 家长控制：先要 PIN，再进设置页（设置页里能改时段、上限、密码等）
        b.btnParent.setOnClickListener {
            ParentTools.askPin(this, "家长控制") { openSettings() }
        }
        b.btnParent.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                .setDuration(120).start()
        }

        PlaylistSync.register(syncListener)
        // OPT-07：清单同步撞上「电视上有没写回 NAS 的修改」时，别静默下发旧设置
        PlaylistSync.onUnsyncedConflict = {
            if (!isFinishing && !isDestroyed && hasWindowFocus()) {
                ParentTools.showUnsyncedConflict(this) { updateHeader() }
            }
        }
        loadPlaylist()
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun loadPlaylist() {
        if (loading) return
        loading = true
        b.pbLoading.visibility = View.VISIBLE
        b.tvEmpty.visibility = View.GONE

        Thread {
            val res = Repo.load()
            runOnUiThread {
                loading = false
                b.pbLoading.visibility = View.GONE
                playlist = res.playlist
                // 时段、密码、通过比例都从清单同步到本地，播放页/锁屏页才能独立读到
                PlaylistSync.applyToLocal(res.playlist)
                render(res.error)
            }
        }.start()
    }

    /** 把家长勾选的应用拼成一个额外的分区，追加在所有片单分区后面 */
    private fun buildAppsSection(pl: Playlist): Section? {
        val cfg: AppsConfig = pl.apps
        if (!cfg.enabled) return null
        val pm = packageManager
        val visible = Extras.visibleApps(cfg)

        val items = visible.mapNotNull { spec ->
            if (AppCatalog.launchIntent(pm, spec) == null) return@mapNotNull null // 只留能启动的
            val custom = cfg.entries.firstOrNull { it.spec == spec }?.label?.takeIf { it.isNotBlank() }
            val label = custom ?: AppCatalog.label(pm, spec) ?: spec.substringBefore('/')
            Item(id = "app:$spec", title = label, app = spec)
        }.toMutableList()
        // 一个都没勾时也留一行占位，家长知道去哪添加；孩子点它只会弹出 PIN
        if (items.isEmpty()) {
            items.add(
                Item(id = "app:__parent__", title = "＋ 家长添加应用", app = AppCatalog.PARENT_ENTRY)
            )
        }

        // 看完今天必看才解锁：复用现有 requires 门禁，卡片上会显示「还差 N 个才解锁」
        val requires = if (cfg.showAfterDaily) pl.gateSections.firstOrNull()?.key else null
        return Section(key = "__apps", title = cfg.title, requires = requires, items = items)
    }

    /**
     * OPT-12「继续看」动态分区：取最近点开过、且确实有未看完进度的条目。
     * 条目 key 一律沿用原路径派生的 key，家长在编辑器里调顺序也不会错位；
     * 剧集页动态列出的单集不在清单里，按路径现造一个（ContinueMath 会确认它还在片单范围内）。
     */
    private fun buildContinueSection(pl: Playlist): Section? {
        if (!Extras.feature("continueWatching")) return null
        val recent = Store.recentPlayed(30)
        if (recent.isEmpty()) return null
        val eligible = ContinueMath.eligible(
            recent, { Store.progress(it) }, { Store.duration(it) }
        )
        if (eligible.isEmpty()) return null

        val all = pl.sections.flatMap { it.items }
        val known = HashMap<String, Item>()
        all.forEach { known[it.key()] = it }
        val exactFiles = all.mapNotNull { it.file }.toHashSet()
        val folders = all.mapNotNull { it.folder }
        val items = eligible.mapNotNull {
            ContinueMath.itemFor(it, known, exactFiles, folders)
        }
        Log.i(TAG, "继续看: recent=${recent.size} eligible=${eligible.size} items=${items.size}")
        if (items.isEmpty()) return null
        return Section(
            key = ContinueMath.SECTION_KEY,
            title = ContinueMath.SECTION_TITLE,
            items = items,
            icon = "play"
        )
    }

    private fun render(error: String?) {
        val pl = playlist ?: return
        val cfg = pl.nas
        val appSection = buildAppsSection(pl)
        val continueSection = buildContinueSection(pl)
        // 继续看放在最上面：孩子回来第一眼就是没看完的那集；应用分区仍在最后
        val sections = ArrayList<Section>(pl.sections.size + 2)
        if (continueSection != null) sections.add(continueSection)
        sections.addAll(pl.sections)
        if (appSection != null) sections.add(appSection)
        val shown = pl.copy(sections = sections)

        if (shown.sections.isEmpty()) {
            b.tvEmpty.visibility = View.VISIBLE
            b.tvEmpty.text = buildString {
                append(error ?: "清单是空的")
                append("\n\n")
                append("请在 NAS 的共享文件夹里放一份清单：\n")
                append("data/curated/tv.json\n\n")
                append("（这个 App 只显示清单里写的东西）")
            }
            b.rvSections.adapter = null
            adapter = null
            return
        }

        b.tvEmpty.visibility = if (error != null) View.VISIBLE else View.GONE
        if (error != null) {
            b.tvEmpty.text = error
            b.tvEmpty.postDelayed({ b.tvEmpty.visibility = View.GONE }, 6000)
        }

        // 自动刷新时不重建 adapter：复用后纵向滚动、每个分区的横向位置、
        // 「先跳过必看」状态和焦点都尽量保留，孩子正在浏览时不会被打断。
        val existing = adapter
        if (error == null && existing != null) {
            val hadFocus = b.rvSections.hasFocus()
            existing.update(shown, cfg)
            updateHeader()
            if (hadFocus) b.rvSections.post {
                if (b.rvSections.findFocus() == null) focusFirstCard()
            }
            return
        }

        val a = SectionsAdapter(
            this, shown, cfg, skipGateOnce,
            onItemClick = { section, item -> onItemClick(section, item) },
            onItemFocus = { section, item ->
                lastFocusSection = section.key
                lastFocusItem = item.key()
            }
        )
        adapter = a
        b.rvSections.adapter = a
        updateHeader()

        b.rvSections.post { focusFirstCard() }
    }

    private fun focusFirstCard() {
        val a = adapter ?: return
        for (i in 0 until a.itemCount) {
            val vh = b.rvSections.findViewHolderForAdapterPosition(i) as? SectionsAdapter.SectionVH ?: continue
            val child = vh.row.getChildAt(0)
            if (child != null && child.isFocusable) {
                child.requestFocus()
                return
            }
        }
    }

    /**
     * 把焦点放回离开前那张卡（从播放页、剧集页回来时用）。
     * 分区/条目已经不在清单里就退回第一张卡。
     */
    private fun restoreFocus() {
        val a = adapter ?: return
        val secKey = lastFocusSection
        val itemKey = lastFocusItem
        if (secKey == null || itemKey == null) {
            focusFirstCard()
            return
        }
        val si = a.indexOfSection(secKey)
        val ii = if (si >= 0) a.indexOfItem(si, itemKey) else -1
        if (si < 0 || ii < 0) {
            focusFirstCard()
            return
        }
        (b.rvSections.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(si, 0)
        b.rvSections.post {
            val vh = b.rvSections.findViewHolderForAdapterPosition(si) as? SectionsAdapter.SectionVH
            val row = vh?.row
            if (row == null) {
                focusFirstCard()
                return@post
            }
            (row.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(ii, 0)
            row.post {
                val target = row.findViewHolderForAdapterPosition(ii)?.itemView
                val ok = target != null && target.isFocusable && target.requestFocus()
                if (!ok) focusFirstCard()
            }
        }
    }

    private fun updateHeader() {
        val a = adapter ?: return
        val total = a.gateTotal()
        val left = a.gateRemaining()
        b.tvGreeting.text = if (total > 0 && left == 0) {
            listOf(
                "今天的都做完啦", "太棒了，全部完成", "今天也坚持住了",
                "盖章成功，去领勋章", "做得好，可以去挑片啦"
            ).random()
        } else "今天要看的"
        b.tvStatus.text = when {
            total == 0 -> ""
            left == 0 -> "必修已完成 $total/$total"
            else -> "必修还剩 $left 个"
        }

        // OPT-14：把「还差几个」画成一条进度条，不用孩子自己去数打勾
        if (total > 0) {
            val done = total - left
            b.taskBar.visibility = View.VISIBLE
            b.tvTask.text = "今天任务 $done/$total"
            b.pbTask.max = total
            b.pbTask.progress = done
        } else {
            b.taskBar.visibility = View.GONE
        }

        // 今天观看时长 / 每日上限
        val st = GateKeeper.status()
        b.tvTime.text = if (st.limitMinutes > 0)
            "今天 ${st.usedMinutes}/${st.limitMinutes} 分钟"
        else "今天 ${st.usedMinutes} 分钟"

        // 勋章小红点
        val n = Badges.newCount()
        b.btnRewards.text = if (n > 0) "我的奖励 · $n 枚新勋章" else "我的奖励"

        // 必看全部完成 → 当天盖章（打卡墙上出现一颗星）
        if (total > 0 && left == 0) {
            val first = !Extras.isCheckedIn(Extras.day())
            Extras.markCheckIn()
            if (first && !celebratedToday) {
                celebratedToday = true
                Toast.makeText(this, "太棒了！今天已打卡，去「我的奖励」看看新勋章", Toast.LENGTH_LONG).show()
                // 每日首次打卡立刻存一次档：这是记录最有价值的那一天（OPT-09）
                StateBackup.nudge("checkin")
            }
        }
    }

    private fun openRewards() {
        startActivity(Intent(this, RewardsActivity::class.java))
    }

    private fun openSettings() {
        focusParentOnResume = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** 打开家长允许的应用，同时开一段「不自动拉回」的窗口并起一段计时会话 */
    private fun launchAllowedApp(spec: String, title: String) {
        // 限额用尽 / 时段外不能靠「点应用入口」绕过去：以前这里没检查，
        // 跳到优酷后自己的界面全在后台，锁屏分支盖不到他（2026-11-10 实机确认）
        val st = GateKeeper.status()
        if (!st.open) {
            Toast.makeText(
                this, st.headline.ifBlank { "现在不能看啦" }, Toast.LENGTH_LONG
            ).show()
            return
        }
        val intent = AppCatalog.launchIntent(packageManager, spec)
        if (intent == null) {
            Toast.makeText(this, "$title 打不开了，可能已经卸载", Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = Extras.apps().maxMinutes
        val pkg = spec.substringBefore('/')
        Extras.grantAppLaunch(pkg, minutes)
        ExtWatchTracker.onLaunch(this, pkg, minutes)
        try {
            startActivity(intent)
            Toast.makeText(this, "$title · 最多 $minutes 分钟，会计入今日时长", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            ExtWatchTracker.onLaunchFailed(this)   // 启动失败要全额退还预扣
            Extras.clearAppLaunch()
            Toast.makeText(this, "打不开 $title：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onItemClick(section: Section, item: Item) {
        val unlocked = skipGateOnce || (adapter?.isUnlocked(section) ?: true)
        if (!unlocked) {
            val left = adapter?.gateRemaining() ?: 0
            Toast.makeText(this, "先做完上面那 $left 个，就能看了", Toast.LENGTH_SHORT).show()
            return
        }

        val appSpec = item.app
        if (!appSpec.isNullOrBlank()) {
            if (appSpec == AppCatalog.PARENT_ENTRY) ParentTools.askPin(this, "家长应用") { showAppPicker() }
            else launchAllowedApp(appSpec, item.title)
            return
        }

        // 必看分区里的视频禁止跳到没看过的地方
        val seekLock = section.gate && !skipGateOnce

        if (item.isFolder) {
            startActivity(
                Intent(this, EpisodesActivity::class.java)
                    .putExtra(EpisodesActivity.EXTRA_FOLDER, item.folder)
                    .putExtra(EpisodesActivity.EXTRA_TITLE, item.title)
                    .putExtra(EpisodesActivity.EXTRA_GATE, seekLock)
            )
        } else {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_FILE, item.file)
                    .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                    .putExtra(PlayerActivity.EXTRA_KEY, item.key())
                    .putExtra(PlayerActivity.EXTRA_GATE_SECTION, if (section.gate) section.key else null)
                    .putExtra(PlayerActivity.EXTRA_SEEK_LOCK, seekLock)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 从播放页回来时进度变了，「继续看」这一行要重算；render 复用同一个 adapter，
        // 不重建 RecyclerView，焦点与横向滚动位置都留着。
        if (playlist != null) render(null)
        else adapter?.notifyDataSetChanged()
        updateHeader()
        // 从播放页/剧集页回来时，重绘会让焦点掉到「我的奖励」按钮上，
        // 这里把它还给离开前那张卡，孩子不用重新找位置。
        if (focusParentOnResume) {
            focusParentOnResume = false
            b.btnParent.post { b.btnParent.requestFocus() }
        } else {
            b.rvSections.post {
                if (b.rvSections.findFocus() == null) restoreFocus()
            }
        }
        ui.removeCallbacks(clockTick)
        ui.postDelayed(clockTick, 5000)
        // 回到主界面时也提醒一次，不必等下一次清单变化（内部有 30 秒节流）
        if (Store.hasUnsyncedEdit) {
            b.root.post { ParentTools.showUnsyncedConflict(this) { updateHeader() } }
        }
        // 重装 / 恢复出厂后本地记录是空的，问一次要不要从 NAS 恢复（只问一次，OPT-09）
        maybeOfferRestore()
    }

    /** 进程内只探一次 NAS 备份，别每次回主界面都读一遍 */
    private var restoreChecked = false

    private fun maybeOfferRestore() {
        if (restoreChecked) return
        restoreChecked = true
        if (Extras.stamps().isNotEmpty()) return
        if (Store.stateRestoreAsked) return
        StateBackup.probe { snap ->
            if (isFinishing || isDestroyed) return@probe
            val s = snap ?: return@probe
            if (!StateMerge.shouldPrompt(Extras.stamps().size, s)) return@probe
            AlertDialog.Builder(this)
                .setTitle("发现上次的观看记录")
                .setMessage(
                    "这台电视上的记录是空的，NAS 上有上次的备份：\n\n" +
                        "${StateMerge.summary(s)}\n\n" +
                        "恢复后打卡墙和播放进度会接上，今天已经看过的时间也会算回去。"
                )
                .setPositiveButton("恢复") { _, _ ->
                    Toast.makeText(this, "正在恢复…", Toast.LENGTH_SHORT).show()
                    StateBackup.importAsync { r ->
                        if (isFinishing || isDestroyed) return@importAsync
                        Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                        if (playlist != null) render(null)
                        updateHeader()
                    }
                }
                .setNeutralButton("稍后", null)
                .setNegativeButton("不用了") { _, _ -> Store.stateRestoreAsked = true }
                .show()
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        PlaylistSync.unregister(syncListener)
        PlaylistSync.onUnsyncedConflict = null
        ui.removeCallbacks(clockTick)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KioskKeys.blocked(keyCode)) {
            nudgeKiosk()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                ParentTools.askPin(this) { showParentMenu() }
                return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_CAPTIONS -> {
                openRewards()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var lastKioskToastAt = 0L

    private fun nudgeKiosk() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKioskToastAt < 3000) return
        lastKioskToastAt = now
        Toast.makeText(this, "家长选项请按菜单键输入密码", Toast.LENGTH_SHORT).show()
    }

    // 输密码 / 勾应用 / 退出到桌面 这几个动作和「家长控制」页共用 ParentTools，
    // 免得两个入口各写一份、改了一边忘另一边。

    private fun showParentMenu() {
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "家长控制（时段 / 上限 / 密码）" to { openSettings() },
            "重新读取清单" to {
                com.family.curated.data.Smb.reset()
                loadPlaylist()
            },
            "选择允许显示的应用" to { showAppPicker() },
            "恢复清单里的应用列表" to {
                Extras.clearSelectedApps()
                render(null)
                Toast.makeText(this, "已恢复清单默认的应用列表", Toast.LENGTH_SHORT).show()
            },
            "今天全部重来（清掉完成记录）" to {
                Store.clearTodayRecursive()
                skipGateOnce = false
                adapter?.setSkipGate(false)
                updateHeader()
                Toast.makeText(this, "已清空今天的记录", Toast.LENGTH_SHORT).show()
            },
            (if (skipGateOnce) "恢复必看限制" else "这次先跳过必看") to {
                skipGateOnce = !skipGateOnce
                adapter?.setSkipGate(skipGateOnce)
                Toast.makeText(
                    this,
                    if (skipGateOnce) "本次已跳过必看" else "已恢复必看限制",
                    Toast.LENGTH_SHORT
                ).show()
            },
            "清空所有播放进度" to {
                Store.resetAllProgress()
                com.family.curated.data.Covers.clear()
                adapter?.notifyDataSetChanged()
                updateHeader()
                Toast.makeText(this, "已清空全部进度", Toast.LENGTH_SHORT).show()
            },
            "打卡墙 · 勋章墙" to { openRewards() },
            "临时放行 15 分钟" to {
                Extras.grantTemp(15)
                Toast.makeText(this, "已临时放行 15 分钟", Toast.LENGTH_SHORT).show()
            },
            (if (Extras.kioskExitActive()) "结束家长放行（恢复儿童锁）" else "退出到桌面（家长维护）") to {
                if (Extras.kioskExitActive()) {
                    Extras.clearKioskExit()
                    Toast.makeText(this, "儿童锁已恢复", Toast.LENGTH_SHORT).show()
                } else {
                    ParentTools.exitToLauncher(this)
                }
            }
        )
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("家长选项")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 勾选孩子能看到的应用入口 */
    private fun showAppPicker() {
        ParentTools.pickApps(this) { render(null) }
    }
}
