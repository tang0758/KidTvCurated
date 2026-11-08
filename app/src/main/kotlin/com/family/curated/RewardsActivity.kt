package com.family.curated

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.curated.data.Badge
import com.family.curated.data.Badges
import com.family.curated.data.Extras
import com.family.curated.data.KioskKeys
import com.family.curated.data.Stats
import com.family.curated.databinding.ActivityRewardsBinding
import com.family.curated.databinding.ItemBadgeBinding
import com.family.curated.databinding.ItemDayBinding
import java.util.Calendar

/** 打卡墙 + 勋章墙 + 观看数据 */
class RewardsActivity : AppCompatActivity() {

    private lateinit var b: ActivityRewardsBinding
    private var monthOffset = 0
    private var stats: Stats = Badges.stats()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KioskKeys.systemBlocked(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Extras.init(this)
        b = ActivityRewardsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.rvDays.layoutManager = GridLayoutManager(this, 7)
        b.rvBadges.layoutManager = GridLayoutManager(this, 2)
        b.rvDays.itemAnimator = null
        b.rvBadges.itemAnimator = null

        b.btnPrev.setOnClickListener { shiftMonth(-1) }
        b.btnNext.setOnClickListener { shiftMonth(1) }

        render()
        b.btnPrev.post { b.btnPrev.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        stats = Badges.stats()
        Extras.markBadgesSeen(Badges.unlockedIds())
        render()
    }

    private fun shiftMonth(delta: Int) {
        val next = monthOffset + delta
        if (next > 0 || next < -24) return
        monthOffset = next
        render()
    }

    private fun render() {
        stats = Badges.stats()

        val cal = Calendar.getInstance().apply {
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val firstDow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7  // 周一 = 0
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        b.tvMonth.text = String.format("%d 年 %d 月", year, month + 1)
        b.tvStreak.text = "连续 ${stats.streak} 天"
        b.tvToday.text = "今天 ${stats.todayMinutes} 分钟"

        val cells = ArrayList<Int?>(42)
        repeat(firstDow) { cells.add(null) }
        for (d in 1..days) cells.add(d)
        b.rvDays.adapter = DayAdapter(year, month, cells, Extras.stamps())

        val got = Badges.unlocked().map { it.id }.toSet()
        b.tvBadgeCount.text = "${got.size} / ${Badges.all.size}"
        b.rvBadges.adapter = BadgeAdapter(stats, got)
        b.tvStats.text = buildString {
            append("本周观看 ${(Extras.watchMsWeek() / 60_000L)} 分钟 · 累计 ${stats.totalMinutes} 分钟\n")
            append("累计看完 ${stats.totalVideos} 个视频 · 打卡 ${stats.totalDays} 天 · 最长连击 ${stats.bestStreak} 天")
        }
        b.tvStampHint.text = if (Extras.isCheckedIn(Extras.day()))
            "今天已经盖章啦，明天继续！" else "把当天「必看」全看完，就会盖上一颗星"
    }

    private inner class DayAdapter(
        private val year: Int,
        private val month: Int,
        private val cells: List<Int?>,
        private val stamps: Set<String>
    ) : RecyclerView.Adapter<DayAdapter.VH>() {

        inner class VH(val bind: ItemDayBinding) : RecyclerView.ViewHolder(bind.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDayBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = cells.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val d = cells[position]
            if (d == null) {
                h.bind.tvDay.text = ""
                h.bind.tvStamp.text = ""
                h.bind.cell.isFocusable = false
                h.bind.cell.alpha = 0f
                return
            }
            h.bind.cell.alpha = 1f
            h.bind.cell.isFocusable = true
            val date = String.format("%04d-%02d-%02d", year, month + 1, d)
            val today = date == Extras.day()
            val stamped = stamps.contains(date)
            val future = date > Extras.day()

            h.bind.tvDay.text = d.toString()
            h.bind.tvDay.setTextColor(
                when {
                    stamped -> getColor(R.color.accent)
                    today -> getColor(R.color.text_primary)
                    future -> getColor(R.color.text_dim)
                    else -> getColor(R.color.text_secondary)
                }
            )
            h.bind.tvStamp.text = when {
                stamped -> "★"
                today -> "今天"
                future -> ""
                else -> "·"
            }
            h.bind.tvStamp.setTextColor(
                if (stamped) getColor(R.color.accent) else getColor(R.color.text_dim)
            )
            h.bind.cell.setOnClickListener {
                // 点一下只是给个反馈，不做任何修改（盖章只由完成必看触发）
            }
        }
    }

    private inner class BadgeAdapter(
        private val s: Stats,
        private val got: Set<String>
    ) : RecyclerView.Adapter<BadgeAdapter.VH>() {

        inner class VH(val bind: ItemBadgeBinding) : RecyclerView.ViewHolder(bind.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemBadgeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = Badges.all.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val badge: Badge = Badges.all[position]
            val unlocked = badge.id in got
            val cur = badge.progress(s).coerceAtMost(badge.goal)

            h.bind.tvGlyph.text = badge.glyph
            h.bind.tvGlyph.setTextColor(
                if (unlocked) getColor(R.color.accent) else getColor(R.color.text_dim)
            )
            h.bind.tvName.text = badge.name
            h.bind.tvName.setTextColor(
                if (unlocked) getColor(R.color.text_primary) else getColor(R.color.text_secondary)
            )
            h.bind.tvDesc.text = badge.desc
            h.bind.tvProgress.text = if (unlocked) "已解锁"
            else if (badge.goal > 1) "进度 $cur / ${badge.goal}" else "还没解锁"
        }
    }
}
