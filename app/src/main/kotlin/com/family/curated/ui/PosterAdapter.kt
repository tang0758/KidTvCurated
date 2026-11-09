package com.family.curated.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.family.curated.R
import com.family.curated.data.AppCatalog
import com.family.curated.data.Covers
import com.family.curated.data.Item
import com.family.curated.data.NasConfig
import com.family.curated.data.Store
import kotlin.math.roundToInt

/**
 * 横向海报行。遥控器左右移动，焦点卡片放大。
 *
 * 焦点表现（电视 4~5 米外要一眼看出来）：
 *  - 放大到 1.10，同时卡片上浮（elevation）带金色投影
 *  - 最上层画一圈金色发光边框（focus_ring），不再被封面图盖住
 *  - 同一排其它卡片压暗到 72%，形成强烈的「选中/未选中」对比
 *  - 标题在获得焦点时加粗，所在分区的标题也会变成金色
 */
class PosterAdapter(
    private val ctx: Context,
    private val cfg: NasConfig?,
    private val items: List<Item>,
    private val locked: Boolean,
    private val showDone: Boolean,
    private val onRowFocus: ((Boolean) -> Unit)? = null,
    private val onClick: (Item) -> Unit
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private val density = ctx.resources.displayMetrics.density
    private val coverW = (200 * density).roundToInt()
    private val coverH = (284 * density).roundToInt()
    private val accent = ctx.getColor(R.color.accent)

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: View = v.findViewById(R.id.card)
        val cover: ImageView = v.findViewById(R.id.ivCover)
        val fallback: TextView = v.findViewById(R.id.tvFallback)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val progress: TextView = v.findViewById(R.id.tvProgress)
        val lockOverlay: View = v.findViewById(R.id.lockOverlay)
        val lockText: TextView = v.findViewById(R.id.tvLock)
        val ring: View = v.findViewById(R.id.focusRing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_poster, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.title.text = item.title
        // 没封面时中央只放首字做占位，完整标题留在底部，避免重复
        holder.fallback.text = item.title.trim().take(2)

        // 复用卡片时先还原成海报状态，否则应用图标的居中/内边距会串到视频卡上
        holder.cover.setImageDrawable(null)
        holder.cover.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.cover.setPadding(0, 0, 0, 0)
        holder.cover.visibility = View.INVISIBLE
        holder.fallback.visibility = View.VISIBLE

        val appSpec = item.app
        if (!appSpec.isNullOrBlank()) {
            // 应用入口直接用系统图标，不去 NAS 找同名封面
            val icon = AppCatalog.icon(ctx.packageManager, appSpec)
            if (icon != null) {
                val pad = (46 * density).roundToInt()
                holder.cover.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.cover.setPadding(pad, pad, pad, pad)
                holder.cover.setImageDrawable(icon)
                holder.cover.visibility = View.VISIBLE
                holder.fallback.visibility = View.GONE
            }
        } else if (cfg != null) {
            Covers.load(holder.cover, cfg, item, coverW, coverH) { bmp ->
                if (bmp != null) {
                    holder.cover.setImageBitmap(bmp)
                    holder.cover.visibility = View.VISIBLE
                    holder.fallback.visibility = View.GONE
                } else {
                    holder.cover.visibility = View.INVISIBLE
                    holder.fallback.visibility = View.VISIBLE
                }
            }
        }

        val done = showDone && Store.isDoneToday(item.key())
        holder.progress.visibility = if (done) View.VISIBLE else View.GONE
        holder.progress.text = "✓ 完成"

        holder.lockOverlay.visibility = if (locked) View.VISIBLE else View.GONE
        holder.lockText.visibility = if (locked) View.VISIBLE else View.GONE

        // 复用时先把焦点样式归位；如果这一格仍持有焦点，下面立刻重新套上
        resetFocusLook(holder)

        holder.card.setOnFocusChangeListener { v, hasFocus ->
            applyFocusLook(holder, hasFocus)
            onRowFocus?.invoke(hasFocus)
        }

        holder.card.setOnClickListener { onClick(item) }

        holder.card.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (position == 0) return@setOnKeyListener true
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (position == itemCount - 1) return@setOnKeyListener true
                }
            }
            false
        }

        if (holder.card.hasFocus()) applyFocusLook(holder, true)
    }

    private fun resetFocusLook(holder: VH) {
        holder.card.animate().cancel()
        holder.card.scaleX = 1f
        holder.card.scaleY = 1f
        holder.card.elevation = 0f
        holder.card.alpha = 1f
        holder.ring.visibility = View.GONE
        holder.ring.alpha = 1f
        holder.title.setTypeface(null, Typeface.NORMAL)
        holder.title.alpha = 0.9f
    }

    /**
     * 焦点样式。放大 + 上浮 + 金边 + 同排其它卡片压暗。
     * 放大 1.10 会超出原来的行高，所以 item_section / RecyclerView 上必须 clipChildren=false，
     * 否则放大的部分会被裁掉——这正是以前「焦点动画不明显」的原因之一。
     */
    private fun applyFocusLook(holder: VH, hasFocus: Boolean) {
        val card = holder.card

        if (hasFocus) {
            holder.ring.visibility = View.VISIBLE
            holder.ring.alpha = 0.25f
            holder.ring.animate().alpha(1f).setDuration(120).start()
        } else {
            holder.ring.visibility = View.GONE
        }

        card.animate()
            .scaleX(if (hasFocus) 1.10f else 1f)
            .scaleY(if (hasFocus) 1.10f else 1f)
            .setDuration(if (hasFocus) 170 else 130)
            .setInterpolator(if (hasFocus) DecelerateInterpolator() else AccelerateInterpolator())
            .start()

        card.elevation = if (hasFocus) 20f * density else 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 金边配金色投影，暗背景上能看出「浮起来」
            card.outlineSpotShadowColor = accent
            card.outlineAmbientShadowColor = accent
        }

        holder.title.setTypeface(null, if (hasFocus) Typeface.BOLD else Typeface.NORMAL)
        holder.title.alpha = 1f

        dimSiblings(holder, hasFocus)
    }

    /** 有焦点时把同一排其它卡片压暗，焦点卡片的对比度立刻拉开 */
    private fun dimSiblings(holder: VH, focused: Boolean) {
        val row = holder.card.parent as? ViewGroup ?: return
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i) ?: continue
            if (child === holder.card) continue
            child.animate().alpha(if (focused) 0.72f else 1f).setDuration(150).start()
        }
    }
}
