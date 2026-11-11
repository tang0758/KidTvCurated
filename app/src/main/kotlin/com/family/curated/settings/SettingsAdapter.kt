package com.family.curated.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.family.curated.R
import com.family.curated.databinding.ItemSettingBinding

/**
 * 设置列表：分组标题占满整行（两列布局时靠 `SpanSizeLookup` 拉通），其余两列排。
 *
 * 只认 [Entry]，不认业务 —— 想加一行设置项，去对应分组的 `rows()` 里加。
 */
class SettingsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
        val inflater = LayoutInflater.from(parent.context)
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

    class GroupVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tv: TextView = v as TextView
        fun set(title: String) {
            tv.text = title
        }
    }

    class RowVH(val bind: ItemSettingBinding) : RecyclerView.ViewHolder(bind.root) {
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

    companion object {
        const val TYPE_GROUP = 0
        const val TYPE_ROW = 1
    }
}
