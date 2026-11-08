package com.family.curated.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 */
class PosterAdapter(
    private val ctx: Context,
    private val cfg: NasConfig?,
    private val items: List<Item>,
    private val locked: Boolean,
    private val showDone: Boolean,
    private val onClick: (Item) -> Unit
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private val density = ctx.resources.displayMetrics.density
    private val coverW = (200 * density).roundToInt()
    private val coverH = (284 * density).roundToInt()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: View = v.findViewById(R.id.card)
        val cover: ImageView = v.findViewById(R.id.ivCover)
        val fallback: TextView = v.findViewById(R.id.tvFallback)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val progress: TextView = v.findViewById(R.id.tvProgress)
        val lockOverlay: View = v.findViewById(R.id.lockOverlay)
        val lockText: TextView = v.findViewById(R.id.tvLock)
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

        holder.card.setOnFocusChangeListener { v, hasFocus ->
            val target = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(target).scaleY(target).setDuration(140)
                .setInterpolator(DecelerateInterpolator()).start()
            v.elevation = if (hasFocus) 18f * density else 0f
            holder.title.alpha = if (hasFocus) 1f else 0.9f
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
    }
}
