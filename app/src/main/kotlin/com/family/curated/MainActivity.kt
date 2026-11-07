package com.family.curated

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
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
import com.family.curated.data.Badges
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.data.Item
import com.family.curated.data.Playlist
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

    private val ui = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateHeader()
            ui.postDelayed(this, 5000)
        }
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
                Extras.saveSchedule(res.playlist.schedule)
                Extras.savePin(res.playlist.pin)
                Extras.savePassRatio(res.playlist.passRatio)
                render(res.error)
            }
        }.start()
    }

    private fun render(error: String?) {
        val pl = playlist ?: return
        val cfg = pl.nas

        if (pl.sections.isEmpty()) {
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

        val a = SectionsAdapter(this, pl, cfg, skipGateOnce) { section, item -> onItemClick(section, item) }
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

    private fun onItemClick(section: Section, item: Item) {
        val unlocked = skipGateOnce || (adapter?.isUnlocked(section) ?: true)
        if (!unlocked) {
            val left = adapter?.gateRemaining() ?: 0
            Toast.makeText(this, "先做完上面那 $left 个，就能看了", Toast.LENGTH_SHORT).show()
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
        ui.removeCallbacks(clockTick)
        ui.postDelayed(clockTick, 5000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(clockTick)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        val items = arrayOf(
            "重新读取清单",
            "今天全部重来（清掉完成记录）",
            if (skipGateOnce) "恢复必看限制" else "这次先跳过必看",
            "清空所有播放进度",
            "打卡墙 · 勋章墙",
            "临时放行 15 分钟"
        )
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("家长选项")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        com.family.curated.data.Smb.reset()
                        loadPlaylist()
                    }
                    1 -> {
                        Store.clearTodayRecursive()
                        skipGateOnce = false
                        adapter?.setSkipGate(false)
                        updateHeader()
                        Toast.makeText(this, "已清空今天的记录", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        skipGateOnce = !skipGateOnce
                        adapter?.setSkipGate(skipGateOnce)
                        Toast.makeText(
                            this,
                            if (skipGateOnce) "本次已跳过必看" else "已恢复必看限制",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    3 -> {
                        Store.resetAllProgress()
                        com.family.curated.data.Covers.clear()
                        adapter?.notifyDataSetChanged()
                        updateHeader()
                        Toast.makeText(this, "已清空全部进度", Toast.LENGTH_SHORT).show()
                    }
                    4 -> openRewards()
                    5 -> {
                        Extras.grantTemp(15)
                        Toast.makeText(this, "已临时放行 15 分钟", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
