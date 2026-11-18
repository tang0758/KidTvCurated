package com.family.curated.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.curated.R
import com.family.curated.data.ClockGuard
import com.family.curated.data.Extras
import com.family.curated.data.Item
import com.family.curated.data.LearningLibrary
import com.family.curated.data.NasConfig
import com.family.curated.data.Playlist
import com.family.curated.data.Section
import com.family.curated.data.Store
import com.family.curated.data.WordCheckInMath

class SectionsAdapter(
    private val ctx: Context,
    private var playlist: Playlist,
    private var cfg: NasConfig?,
    private var skipGate: Boolean = false,
    private val onWordCheckInClick: () -> Unit,
    private val onItemClick: (Section, Item) -> Unit,
    private val onItemFocus: ((Section, Item) -> Unit)? = null,
    private val onItemLongClick: ((Section, Item) -> Boolean)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_WORD = 0
        private const val TYPE_SECTION = 1
        private const val HEADER_COUNT = 1
    }

    private val scrollMemo = HashMap<String, Int>()

    /** 分区在 Adapter 里的真实下标（含第 0 位打卡入口），找不到返回 -1 */
    fun indexOfSection(key: String): Int {
        val idx = playlist.sections.indexOfFirst { it.key == key }
        return if (idx >= 0) idx + HEADER_COUNT else -1
    }

    /** 条目在某个分区（传入 Adapter 下标）里的下标，找不到返回 -1 */
    fun indexOfItem(adapterPos: Int, itemKey: String): Int {
        val secIdx = adapterPos - HEADER_COUNT
        val sec = playlist.sections.getOrNull(secIdx) ?: return -1
        return sec.items.indexOfFirst { it.key() == itemKey || it.id == itemKey }
    }

    fun update(p: Playlist, newCfg: NasConfig? = cfg) {
        playlist = p
        cfg = newCfg
        notifyDataSetChanged()
    }

    fun setSkipGate(v: Boolean) {
        skipGate = v
        notifyDataSetChanged()
    }

    fun sectionAt(adapterPos: Int): Section = playlist.sections[adapterPos - HEADER_COUNT]

    /** 今日单词打卡是否已完成（FR-35 并列门禁） */
    fun wordDoneToday(): Boolean {
        val (allWords, dailyCount) = LearningLibrary.loadOnlineWords(Store.cachedLearningJson)
        return Extras.isWordDoneToday(allWords, dailyCount, ClockGuard.day())
    }

    /** 某个分区是否已解锁（视频必看 + 每日英语打卡并列门禁） */
    fun isUnlocked(section: Section): Boolean {
        if (skipGate) return true
        val req = section.requires ?: return true
        val gate = playlist.sections.firstOrNull { it.key == req } ?: return true
        val videoDone = gate.items.isNotEmpty() && gate.items.all { Store.isDoneToday(it.key()) }
        return WordCheckInMath.canUnlockDailyGate(
            hasVideoGate = true,
            videoGateDone = videoDone,
            wordCheckInEnabled = true,
            wordDoneToday = wordDoneToday()
        )
    }

    fun gateRemaining(): Int {
        return playlist.gateSections.sumOf { sec -> sec.items.count { !Store.isDoneToday(it.key()) } }
    }

    fun gateTotal(): Int = playlist.gateSections.sumOf { it.items.size }

    override fun getItemCount(): Int = playlist.sections.size + HEADER_COUNT

    override fun getItemViewType(position: Int): Int =
        if (position == 0) TYPE_WORD else TYPE_SECTION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(ctx)
        return if (viewType == TYPE_WORD) {
            WordVH(inf.inflate(R.layout.item_word_checkin_banner, parent, false))
        } else {
            SectionVH(inf.inflate(R.layout.item_section, parent, false))
        }
    }

    inner class WordVH(v: View) : RecyclerView.ViewHolder(v) {
        val status: TextView = v.findViewById(R.id.tvWordEntryStatus)
    }

    inner class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvSectionTitle)
        val icon: ImageView = v.findViewById(R.id.ivSectionIcon)
        val badge: TextView = v.findViewById(R.id.tvBadge)
        val row: RecyclerView = v.findViewById(R.id.rvRow)
        var boundKey: String? = null
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is WordVH) {
            bindWordHeader(holder)
            return
        }
        val sectionHolder = holder as SectionVH
        val section = playlist.sections[position - HEADER_COUNT]
        sectionHolder.title.text = section.title
        sectionHolder.title.setTextColor(ctx.getColor(R.color.text_primary))

        val iconRes = SectionIcons.resId(section.icon)
        if (iconRes == null) {
            sectionHolder.icon.visibility = View.GONE
            sectionHolder.icon.setImageDrawable(null)
        } else {
            sectionHolder.icon.setImageResource(iconRes)
            sectionHolder.icon.visibility = View.VISIBLE
        }

        val unlocked = isUnlocked(section)
        val doneCount = section.items.count { Store.isDoneToday(it.key()) }
        val total = section.items.size

        sectionHolder.badge.text = when {
            !unlocked -> {
                val req = section.requires?.let { key -> playlist.sections.firstOrNull { it.key == key } }
                val left = req?.items?.count { !Store.isDoneToday(it.key()) } ?: 0
                if (left > 0) "还差 $left 个才解锁" else ctx.getString(R.string.word_need_checkin)
            }
            section.gate && total > 0 -> "必修 $doneCount/$total"
            total > 0 -> "$total 个"
            else -> ""
        }
        sectionHolder.badge.setTextColor(
            if (unlocked && section.gate && doneCount == total && total > 0)
                ctx.getColor(R.color.done_green)
            else if (!unlocked) ctx.getColor(R.color.text_dim)
            else ctx.getColor(R.color.text_secondary)
        )

        // 记住滚动位置
        val memo = scrollMemo[section.key] ?: 0

        sectionHolder.row.layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
        sectionHolder.row.adapter = PosterAdapter(
            ctx, cfg, section.items, locked = !unlocked,
            showDone = section.gate,
            // 焦点在这一排时把分区标题点亮，家长/孩子一眼知道自己在看哪一行
            onRowFocus = { has ->
                sectionHolder.title.setTextColor(
                    ctx.getColor(if (has) R.color.accent else R.color.text_primary)
                )
            },
            onItemFocus = { item -> onItemFocus?.invoke(section, item) },
            onLongClick = { item -> onItemLongClick?.invoke(section, item) ?: false }
        ) { item ->
            onItemClick(section, item)
        }
        if (memo > 0) (sectionHolder.row.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(memo, 0)

        sectionHolder.row.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (rv.hasFocus()) scrollMemo[section.key] = lm.findFirstVisibleItemPosition()
            }
        })

        sectionHolder.boundKey = section.key
    }

    private fun bindWordHeader(holder: WordVH) {
        val (allWords, dailyCount) = LearningLibrary.loadOnlineWords(Store.cachedLearningJson)
        val dayKey = ClockGuard.day()
        val queue = Extras.ensureTodayWordQueue(allWords, dailyCount, dayKey)
        val answered = Extras.wordAnswered(dayKey)
        val doneCount = queue.count { it in answered }
        val total = queue.size
        val finished = total == 0 || doneCount >= total
        if (finished) {
            holder.status.text = ctx.getString(R.string.word_entry_done, Extras.wordStreak())
            holder.status.setTextColor(ctx.getColor(R.color.done_green))
        } else {
            holder.status.text = ctx.getString(R.string.word_entry_progress, doneCount, total)
            holder.status.setTextColor(ctx.getColor(R.color.accent))
        }
        holder.itemView.setOnClickListener { onWordCheckInClick() }
        holder.itemView.setOnFocusChangeListener { v, has ->
            // 横条宽度接近屏幕全宽，不横向放大以免左右贴边被裁切，靠 word_entry_bg 金框高亮提示焦点
            v.animate().scaleX(1f).scaleY(if (has) 1.03f else 1f)
                .setDuration(120).start()
        }
    }
}
