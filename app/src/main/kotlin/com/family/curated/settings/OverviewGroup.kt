package com.family.curated.settings

import android.content.Intent
import com.family.curated.ParentTools
import com.family.curated.R
import com.family.curated.ReportActivity
import com.family.curated.data.Extras
import com.family.curated.data.Store

/**
 * 「概览」分组：家长进来最想先看到的两个入口 —— 本周周报，以及「上次没写回 NAS」的重试。
 *
 * 两个都是**条件出现的**：周报被 `features.report` 关掉时整组不出现；
 * 没有未同步修改时也不出现重试项，免得家长对着一条点不动的行发懵。
 */
class OverviewGroup(private val h: SettingsHost) : SettingsGroup {

    override fun rows(): List<Entry> {
        val list = ArrayList<Entry>()
        // OPT-08：周报是家长进这一页最想先看的东西，放在最上面。
        // 走 features.report 开关（默认开）：这一页要是出问题，改一行 JSON 就能收起来。
        if (Extras.feature("report")) {
            list.add(Entry.Group(h.str(R.string.set_group_overview)))
            list.add(
                Entry.Row(
                    h.str(R.string.set_report),
                    h.str(R.string.set_report_desc),
                    h.str(R.string.act_open)
                ) { h.act.startActivity(Intent(h.act, ReportActivity::class.java)) }
            )
        }
        // OPT-07：写回失败不是「弹个 Toast 就过去」，这一项让家长随时能重试
        if (Store.hasUnsyncedEdit) {
            list.add(Entry.Group(h.str(R.string.set_group_unsynced)))
            list.add(
                Entry.Row(
                    h.str(R.string.set_retry_save),
                    h.str(R.string.set_retry_label, Store.unsyncedLabel),
                    h.str(R.string.act_retry)
                ) { ParentTools.showUnsyncedConflict(h.act, force = true) { h.refresh() } }
            )
        }
        return list
    }
}
