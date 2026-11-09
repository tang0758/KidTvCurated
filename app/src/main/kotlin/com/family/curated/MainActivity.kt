package com.family.curated

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.family.curated.data.AppCatalog
import com.family.curated.data.AppsConfig
import com.family.curated.data.Badges
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.data.Item
import com.family.curated.data.KioskKeys
import com.family.curated.data.Playlist
import com.family.curated.data.PlaylistSync
import com.family.curated.data.Repo
import com.family.curated.data.Section
import com.family.curated.data.Store
import com.family.curated.databinding.ActivityMainBinding
import com.family.curated.ui.SectionsAdapter

class MainActivity : AppCompatActivity() {

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

        PlaylistSync.register(syncListener)
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

    private fun render(error: String?) {
        val pl = playlist ?: return
        val cfg = pl.nas
        val appSection = buildAppsSection(pl)
        val shown = if (appSection != null) pl.copy(sections = pl.sections + appSection) else pl

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
            }
        }
    }

    private fun openRewards() {
        startActivity(Intent(this, RewardsActivity::class.java))
    }

    /** 打开家长允许的应用，同时开一段「不自动拉回」的窗口 */
    private fun launchAllowedApp(spec: String, title: String) {
        val intent = AppCatalog.launchIntent(packageManager, spec)
        if (intent == null) {
            Toast.makeText(this, "$title 打不开了，可能已经卸载", Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = Extras.apps().maxMinutes
        Extras.grantAppLaunch(spec.substringBefore('/'), minutes)
        try {
            startActivity(intent)
            Toast.makeText(this, "$title · 最多 $minutes 分钟后自动回小剧场", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
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
            if (appSpec == AppCatalog.PARENT_ENTRY) askPin { showAppPicker() }
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
        adapter?.let {
            it.notifyDataSetChanged()
            updateHeader()
        }
        // 从播放页/剧集页回来时，重绘会让焦点掉到「我的奖励」按钮上，
        // 这里把它还给离开前那张卡，孩子不用重新找位置。
        b.rvSections.post {
            if (b.rvSections.findFocus() == null) restoreFocus()
        }
        ui.removeCallbacks(clockTick)
        ui.postDelayed(clockTick, 5000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        PlaylistSync.unregister(syncListener)
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
                askPin { showParentMenu() }
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

    /**
     * 家长出口：先开一段临时放行，再显式启动别的桌面。
     * 不放行的话前台守卫会立刻把家长拉回来，等于把自己也锁在外面。
     */
    private fun exitToLauncher() {
        val k = Extras.kiosk()
        val configured = ComponentName.unflattenFromString(k.exitComponent)
        if (configured != null) {
            if (launchComponent(configured, k.exitMinutes)) return
        }

        val pm = packageManager
        val candidates = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }

        if (candidates.isEmpty()) {
            Toast.makeText(this, "没找到其它桌面，请在 kiosk.exitComponent 里指定", Toast.LENGTH_LONG).show()
            return
        }
        val labels = candidates.map {
            it.loadLabel(pm).toString() + "  (" + it.activityInfo.packageName + ")"
        }.toTypedArray()
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("退出到哪个桌面？")
            .setItems(labels) { _, which ->
                val ai = candidates[which].activityInfo
                if (!launchComponent(ComponentName(ai.packageName, ai.name), k.exitMinutes)) {
                    Toast.makeText(this, "启动失败，请把组件名写进 kiosk.exitComponent", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchComponent(cmp: ComponentName, minutes: Int): Boolean = try {
        Extras.grantKioskExit(minutes)
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cmp)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Toast.makeText(this, "已临时放行 $minutes 分钟，回来后可在家长菜单结束放行", Toast.LENGTH_LONG).show()
        true
    } catch (e: Exception) {
        Extras.clearKioskExit()
        false
    }

    /** 家长菜单要密码，防止孩子自己解锁 */
    private fun askPin(onOk: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "家长密码（tv.json 的 pin）"
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("家长选项")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                if (input.text.toString() == Extras.pin()) onOk()
                else Toast.makeText(this, "密码不对", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showParentMenu() {
        val actions: List<Pair<String, () -> Unit>> = listOf(
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
                    exitToLauncher()
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

    /** 从电视上已安装的应用里勾选孩子能看到的入口（桌面 / 设置 / 安装器等已过滤） */
    private fun showAppPicker() {
        val catalog = AppCatalog.installed(this)
        if (catalog.isEmpty()) {
            Toast.makeText(this, "没找到可以启动的应用", Toast.LENGTH_LONG).show()
            return
        }
        val selected = Extras.visibleApps()
        val labels = catalog.map { "${it.label}  (${it.spec})" }.toTypedArray()
        val checked = BooleanArray(catalog.size) { catalog[it].spec in selected }

        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("选择孩子能看到的 App（OK 勾选）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val set = catalog.filterIndexed { i, _ -> checked[i] }.map { it.spec }.toSet()
                Extras.saveSelectedApps(set)
                render(null)
                Toast.makeText(this, "已显示 ${set.size} 个应用入口", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("全部隐藏") { _, _ ->
                Extras.saveSelectedApps(emptySet())
                render(null)
                Toast.makeText(this, "已隐藏所有应用入口", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
