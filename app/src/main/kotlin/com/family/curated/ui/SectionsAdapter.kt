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
import com.family.curated.data.Item
import com.family.curated.data.NasConfig
import com.family.curated.data.Playlist
import com.family.curated.data.Section
import com.family.curated.data.Store

class SectionsAdapter(
    private val ctx: Context,
    private var playlist: Playlist,
    private var cfg: NasConfig?,
    private var skipGate: Boolean = false,
    private val onItemClick: (Section, Item) -> Unit,
    private val onItemFocus: ((Section, Item) -> Unit)? = null
) : RecyclerView.Adapter<SectionsAdapter.SectionVH>() {

    private val scrollMemo = HashMap<String, Int>()

    /** 分区在列表里的下标（焦点复位用），找不到返回 -1 */
    fun indexOfSection(key: String): Int =
        playlist.sections.indexOfFirst { it.key == key }

    /** 条目在某个分区里的下标，找不到返回 -1 */
    fun indexOfItem(sectionIndex: Int, itemKey: String): Int {
        val sec = playlist.sections.getOrNull(sectionIndex) ?: return -1
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

    fun sectionAt(i: Int): Section = playlist.sections[i]

    /** 某个分区是否已解锁 */
    fun isUnlocked(section: Section): Boolean {
        if (skipGate) return true
        val req = section.requires ?: return true
        val gate = playlist.sections.firstOrNull { it.key == req } ?: return true
        return gate.items.isNotEmpty() && gate.items.all { Store.isDoneToday(it.key()) }
    }

    fun gateRemaining(): Int {
        return playlist.gateSections.sumOf { sec -> sec.items.count { !Store.isDoneToday(it.key()) } }
    }

    fun gateTotal(): Int = playlist.gateSections.sumOf { it.items.size }

    override fun getItemCount(): Int = playlist.sections.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionVH {
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_section, parent, false)
        return SectionVH(v)
    }

    inner class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvSectionTitle)
        val icon: ImageView = v.findViewById(R.id.ivSectionIcon)
        val badge: TextView = v.findViewById(R.id.tvBadge)
        val row: RecyclerView = v.findViewById(R.id.rvRow)
        var boundKey: String? = null
    }

    override fun onBindViewHolder(holder: SectionVH, position: Int) {
        val section = playlist.sections[position]
        holder.title.text = section.title
        holder.title.setTextColor(ctx.getColor(R.color.text_primary))

        val iconRes = SectionIcons.resId(section.icon)
        if (iconRes == null) {
            holder.icon.visibility = View.GONE
            holder.icon.setImageDrawable(null)
        } else {
            holder.icon.setImageResource(iconRes)
            holder.icon.visibility = View.VISIBLE
        }

        val unlocked = isUnlocked(section)
        val doneCount = section.items.count { Store.isDoneToday(it.key()) }
        val total = section.items.size

        holder.badge.text = when {
            !unlocked -> {
                val req = section.requires?.let { key -> playlist.sections.firstOrNull { it.key == key } }
                val left = req?.items?.count { !Store.isDoneToday(it.key()) } ?: 0
                "还差 $left 个才解锁"
            }
            section.gate && total > 0 -> "必修 $doneCount/$total"
            total > 0 -> "$total 个"
            else -> ""
        }
        holder.badge.setTextColor(
            if (unlocked && section.gate && doneCount == total && total > 0)
                ctx.getColor(R.color.done_green)
            else if (!unlocked) ctx.getColor(R.color.text_dim)
            else ctx.getColor(R.color.text_secondary)
        )

        // 记住滚动位置
        val memo = scrollMemo[section.key] ?: 0

        holder.row.layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
        holder.row.adapter = PosterAdapter(
            ctx, cfg, section.items, locked = !unlocked,
            showDone = section.gate,
            // 焦点在这一排时把分区标题点亮，家长/孩子一眼知道自己在看哪一行
            onRowFocus = { has ->
                holder.title.setTextColor(
                    ctx.getColor(if (has) R.color.accent else R.color.text_primary)
                )
            },
            onItemFocus = { item -> onItemFocus?.invoke(section, item) }
        ) { item ->
            onItemClick(section, item)
        }
        if (memo > 0) (holder.row.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(memo, 0)

        holder.row.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (rv.hasFocus()) scrollMemo[section.key] = lm.findFirstVisibleItemPosition()
            }
        })

        holder.boundKey = section.key
    }
}
