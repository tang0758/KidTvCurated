package com.family.curated.settings

import com.family.curated.BuildConfig
import com.family.curated.R
import com.family.curated.data.ClockGuard
import com.family.curated.data.ExtWatchTracker
import com.family.curated.data.Extras
import com.family.curated.data.Features
import com.family.curated.data.RemoteControl
import com.family.curated.data.Store
import org.json.JSONObject

/**
 * 「系统」分组：功能开关 + 时间守卫 + 版本。
 *
 * 开关统一走 [toggleFeature]：写回 NAS 成功才更新电视本机，出问题改一行 JSON
 * 等一个 15 秒轮询就能回滚。版本行是排障入口，点开能一次看全当前所有开关状态。
 */
class SystemGroup(private val h: SettingsHost) : SettingsGroup {

    /** 开关名 → 界面上的叫法，`showAbout` 与各行标题共用，避免两处对不上 */
    private val featureNames: LinkedHashMap<String, Int> = linkedMapOf(
        "kioskPullback" to R.string.set_pullback,
        "extWatchMs" to R.string.set_extwatch,
        "remainingNotice" to R.string.set_notice,
        "remoteControl" to R.string.set_remote,
        "continueWatching" to R.string.set_continue,
        "autoNext" to R.string.set_autonext,
        "report" to R.string.set_report
    )

    override fun rows(): List<Entry> = buildList {
        add(Entry.Group(h.str(R.string.set_group_system)))
        // 后台拉回是「老行为」，默认开；个别电视上会和投屏/系统弹窗打架，可以在这里关掉
        add(
            Entry.Row(
                h.str(R.string.set_pullback),
                if (Extras.feature("kioskPullback")) h.str(R.string.set_pullback_on)
                else h.str(R.string.set_pullback_off),
                onOff("kioskPullback")
            ) { toggleFeature("kioskPullback", h.str(R.string.set_pullback)) }
        )
        // 外部应用计时默认开：2026-11-10 实机确认孩子从应用入口进优酷 2 小时不入账，
        // 等于绕过每日上限。这是修 bug 不是灰度，装上就生效；出问题在这里关掉，15 秒回滚。
        add(
            Entry.Row(
                h.str(R.string.set_extwatch),
                if (Extras.feature("extWatchMs")) ExtWatchTracker.modeText(h.act)
                else h.str(R.string.set_extwatch_off),
                onOff("extWatchMs")
            ) { toggleFeature("extWatchMs", h.str(R.string.set_extwatch)) }
        )
        add(
            Entry.Row(
                h.str(R.string.set_notice),
                if (Extras.feature("remainingNotice")) h.str(R.string.set_notice_on)
                else h.str(R.string.set_notice_off),
                onOff("remainingNotice")
            ) { toggleFeature("remainingNotice", h.str(R.string.set_notice)) }
        )
        add(
            Entry.Row(
                h.str(R.string.set_remote),
                if (Extras.feature("remoteControl")) h.str(R.string.set_remote_on)
                else h.str(R.string.set_remote_off),
                onOff("remoteControl")
            ) { toggleFeature("remoteControl", h.str(R.string.set_remote)) }
        )
        add(
            Entry.Row(
                h.str(R.string.set_continue),
                if (Extras.feature("continueWatching")) h.str(R.string.set_continue_on)
                else h.str(R.string.set_continue_off),
                onOff("continueWatching")
            ) { toggleFeature("continueWatching", h.str(R.string.set_continue)) }
        )
        // 默认关：连播会让时长消耗明显变快，家长要自己决定要不要打开
        add(
            Entry.Row(
                h.str(R.string.set_autonext),
                if (Extras.feature("autoNext")) h.str(R.string.set_autonext_on)
                else h.str(R.string.set_autonext_off),
                onOff("autoNext")
            ) { toggleFeature("autoNext", h.str(R.string.set_autonext)) }
        )
        // 周报只读、不改变任何行为，默认开；万一某个电视上渲染出问题，这里一键收起入口
        add(
            Entry.Row(
                h.str(R.string.set_report),
                if (Extras.feature("report")) h.str(R.string.set_report_on)
                else h.str(R.string.set_report_off),
                onOff("report")
            ) { toggleFeature("report", h.str(R.string.set_report)) }
        )
        // 改过日期 / 时间被系统校回时才会出现：平时家长不该看到这一行
        val anomalies = ClockGuard.anomalyCount()
        if (anomalies > 0 || ClockGuard.frozen()) {
            add(
                Entry.Row(
                    h.str(R.string.set_clock),
                    h.str(R.string.set_clock_anomaly, anomalies),
                    if (ClockGuard.frozen()) h.str(R.string.set_clock_calibrating)
                    else h.str(R.string.act_calibrate)
                ) {
                    h.act.confirmDialog(
                        h.str(R.string.set_clock_confirm),
                        h.str(R.string.set_clock_confirm_msg)
                    ) {
                        ClockGuard.reset()
                        h.refresh()
                    }
                }
            )
        }
        add(
            Entry.Row(
                h.str(R.string.set_version_row),
                h.str(R.string.set_built_at, BuildConfig.BUILD_TIME),
                "v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
            ) { showAbout() }
        )
    }

    private fun onOff(name: String): String =
        if (Extras.feature(name)) h.str(R.string.common_on) else h.str(R.string.common_off)

    /**
     * 功能开关统一走这一处：写回 NAS 成功才更新电视本机。
     * 打开远程控制时顺手 nudge 一次，不用等下一个 15 秒轮询。
     */
    private fun toggleFeature(name: String, label: String) {
        val next = !Extras.feature(name)
        h.save({ root: JSONObject ->
            val o = root.optJSONObject("features") ?: JSONObject().also { root.put("features", it) }
            o.put(name, next)
        }, {
            Extras.saveFeatures(Features(Extras.features().on + (name to next)))
            if (name == "remoteControl" && next) RemoteControl.nudge()
        }, label)
    }

    /** 排障用：版本、构建时间、功能开关现状一次看全，不用连电脑 */
    private fun showAbout() {
        val switches = featureNames.entries.joinToString("\n") { (k, res) ->
            "${h.str(res)}：${if (Extras.feature(k)) h.str(R.string.word_on) else h.str(R.string.word_off)}"
        }
        val anomalies = ClockGuard.anomalyCount()
        val msg = buildString {
            append(h.str(R.string.set_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            append(h.str(R.string.set_about_built, BuildConfig.BUILD_TIME))
            append(h.str(R.string.set_about_pkg, h.act.packageName))
            append(h.str(R.string.set_about_features))
            append(switches)
            append(h.str(R.string.set_about_ext, Extras.extMsToday() / 60_000L))
            append(h.str(R.string.set_about_mode, ExtWatchTracker.modeText(h.act)))
            append(h.str(R.string.set_about_remote, Store.playlistShare, RemoteControl.controlPath()))
            append(
                h.str(R.string.set_about_last_cmd, Store.lastControlAt.ifBlank { h.str(R.string.word_none) })
            )
            if (Extras.remoteLockActive()) append(
                h.str(R.string.set_about_remote_lock, Extras.remoteLockRemainingMs() / 60_000L + 1)
            )
            if (anomalies > 0) {
                append(h.str(R.string.set_about_anomaly, anomalies))
                if (ClockGuard.frozen()) append(h.str(R.string.set_about_frozen))
            }
        }
        h.act.darkDialog()
            .setTitle(h.str(R.string.set_about_title))
            .setMessage(msg)
            .setPositiveButton(h.str(R.string.common_got_it), null)
            .show()
    }
}
