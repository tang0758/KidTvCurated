package com.family.curated.settings

import android.text.InputType
import com.family.curated.ParentTools
import com.family.curated.R
import com.family.curated.data.AppsConfig
import com.family.curated.data.Extras
import org.json.JSONObject

/**
 * 「规则」分组：家长 PIN、必修通过率、应用入口。
 *
 * 这三项决定了「孩子能不能绕出去」，改动都直接写回 tv.json；
 * 应用入口要多一步勾选已安装应用（[ParentTools.pickApps]），和其它两项不一样。
 */
class RulesGroup(private val h: SettingsHost) : SettingsGroup {

    override fun rows(): List<Entry> {
        val apps = Extras.apps()
        return buildList<Entry> {
            add(Entry.Group(h.str(R.string.set_group_rules)))
            add(
                Entry.Row(h.str(R.string.set_pin), h.str(R.string.set_pin_hint), h.str(R.string.act_edit)) { askPin() }
            )
            add(
                Entry.Row(
                    h.str(R.string.set_pass_ratio),
                    h.str(R.string.set_pass_ratio_hint),
                    "${(Extras.passRatio() * 100).toInt()}%"
                ) { choosePassRatio() }
            )
            add(
                Entry.Row(
                    h.str(R.string.set_apps),
                    if (apps.enabled) h.str(R.string.set_apps_hint_on) else h.str(R.string.set_apps_hint_off),
                    if (apps.enabled) h.str(R.string.set_apps_count, Extras.visibleApps(apps).size)
                    else h.str(R.string.set_not_enabled)
                ) { editApps() }
            )
        }
    }

    private fun askPin() {
        val input = h.act.inputField(
            hint = h.str(R.string.set_pin_input_hint),
            type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        val dialog = h.act.darkDialog()
            .setTitle(h.str(R.string.set_pin_title))
            .setView(input)
            .setPositiveButton(h.str(R.string.common_save)) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                if (v.length < 4 || v.length > 8 || !v.all { it.isDigit() }) {
                    h.toast(h.str(R.string.set_pin_invalid), long = true)
                    return@setPositiveButton
                }
                h.save({ root: JSONObject ->
                    root.put("pin", v)
                    root.optJSONObject("schedule")?.put("pin", v)
                }, { Extras.savePin(v) }, h.str(R.string.set_pin))
            }
            .setNegativeButton(h.str(R.string.common_cancel), null)
            .create()
        dialog.show()
        ParentTools.submitOnEnter(input, dialog)
    }

    private fun choosePassRatio() {
        // 用 Double 而不是 Float：Float 0.8f 转成 Double 会写成 0.800000011920929
        val values = listOf(0.80, 0.85, 0.90, 0.92, 0.95, 1.0)
        val labels = values.map { "${(it * 100).toInt()}%" }
        val cur = Math.round(Extras.passRatio() * 100)
        val idx = values.indexOfFirst { (it * 100).toInt() == cur }
        h.act.pickDialog(h.str(R.string.set_pass_ratio), labels, idx) { i ->
            val v = values[i]
            h.save({ root -> root.put("passRatio", v) },
                { Extras.savePassRatio(v.toFloat()) }, h.str(R.string.set_pass_ratio))
        }
    }

    private fun editApps() {
        val cfg: AppsConfig = Extras.apps()
        if (cfg.enabled) {
            ParentTools.pickApps(h.act) { h.refresh() }
            return
        }
        h.act.darkDialog()
            .setTitle(h.str(R.string.set_apps_confirm))
            .setMessage(h.str(R.string.set_apps_confirm_msg, cfg.title))
            .setPositiveButton(h.str(R.string.common_on)) { _, _ ->
                h.save({ root ->
                    val o = root.optJSONObject("apps") ?: JSONObject().also { root.put("apps", it) }
                    o.put("enabled", true)
                }, {
                    Extras.saveApps(cfg.copy(enabled = true))
                    ParentTools.pickApps(h.act) { h.refresh() }
                }, h.str(R.string.set_apps))
            }
            .setNegativeButton(h.str(R.string.common_cancel), null)
            .show()
    }
}
