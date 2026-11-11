package com.family.curated.settings

import com.family.curated.ParentTools
import com.family.curated.R
import com.family.curated.data.Covers
import com.family.curated.data.Diag
import com.family.curated.data.Extras
import com.family.curated.data.PlaylistSync
import com.family.curated.data.Repo
import com.family.curated.data.Smb
import com.family.curated.data.StateBackup
import com.family.curated.data.Store

/**
 * 「维护」分组：临时放行、写回失败后的手工重试入口之外，家长真正会用到的几个动作。
 *
 * 这些动作**都不改 tv.json**（除了「重载片单」是重新读一遍），所以没有写回失败的坑；
 * 担心的反而是误触 —— 清今日记录、清全部进度都带二次确认。
 */
class MaintGroup(private val h: SettingsHost) : SettingsGroup {

    override fun rows(): List<Entry> = buildList {
        add(Entry.Group(h.str(R.string.set_group_maint)))
        add(
            Entry.Row(
                h.str(R.string.set_temp_release),
                if (Extras.tempRemainingMs() > 0L)
                    h.str(R.string.set_temp_hint_active) else h.str(R.string.set_temp_hint_idle),
                tempText()
            ) { chooseTemp() }
        )
        add(
            Entry.Row(h.str(R.string.set_reset_today), h.str(R.string.set_reset_today_hint), "") {
                h.act.confirmDialog(
                    h.str(R.string.set_reset_today_confirm),
                    h.str(R.string.set_reset_today_msg)
                ) {
                    Store.clearTodayRecursive()
                    h.toast(h.str(R.string.set_reset_today_done))
                    h.refresh()
                }
            }
        )
        add(
            Entry.Row(h.str(R.string.set_reset_progress), h.str(R.string.set_reset_progress_hint), "") {
                h.act.confirmDialog(
                    h.str(R.string.set_reset_progress_confirm),
                    h.str(R.string.set_reset_progress_msg)
                ) {
                    Store.resetAllProgress()
                    Covers.clear()
                    // 清完立刻把「空的」状态写回 NAS：否则下次恢复记录时，
                    // 备份里那些旧进度会被重新合回来，家长的操作等于没做（OPT-09）
                    StateBackup.exportAsync("reset") { }
                    h.toast(h.str(R.string.set_reset_progress_done))
                    h.refresh()
                }
            }
        )
        add(
            Entry.Row(
                h.str(R.string.set_backup),
                h.str(R.string.set_backup_summary, StateBackup.lastSavedText(), Extras.stamps().size),
                h.str(R.string.act_backup)
            ) { showStateBackup() }
        )
        add(
            Entry.Row(
                h.str(R.string.set_diag),
                Diag.statusText(),
                if (Diag.pending() > 0) h.str(R.string.act_upload) else h.str(R.string.act_view)
            ) { showDiag() }
        )
        add(
            Entry.Row(h.str(R.string.set_reload), h.str(R.string.set_reload_hint), "") { reloadPlaylist() }
        )
        add(
            Entry.Row(
                h.str(R.string.set_exit_launcher),
                if (Extras.kioskExitActive()) h.str(R.string.set_exit_hint_lock)
                else h.str(R.string.set_exit_hint_free),
                ""
            ) {
                if (Extras.kioskExitActive()) {
                    Extras.clearKioskExit()
                    h.toast(h.str(R.string.set_kiosk_restored))
                    h.refresh()
                } else {
                    ParentTools.exitToLauncher(h.act)
                }
            }
        )
    }

    // ---------------- 临时放行 ----------------

    private fun tempText(): String {
        val left = Extras.tempRemainingMs()
        if (left <= 0L) return h.str(R.string.set_not_released)
        return h.str(R.string.set_temp_left, (left / 60_000L) + 1)
    }

    private fun chooseTemp() {
        val active = Extras.tempRemainingMs() > 0L
        val labels = (if (active) arrayListOf(h.str(R.string.set_temp_end)) else arrayListOf()) +
            listOf(
                h.str(R.string.temp_15), h.str(R.string.temp_30),
                h.str(R.string.temp_60), h.str(R.string.temp_120)
            )
        val minutes = listOf(15, 30, 60, 120)
        h.act.darkDialog()
            .setTitle(h.str(R.string.set_temp_release))
            .setItems(labels.toTypedArray()) { _, which ->
                if (active && which == 0) {
                    Extras.clearTemp()
                    h.toast(h.str(R.string.set_temp_restored))
                } else {
                    val idx = if (active) which - 1 else which
                    val m = minutes.getOrNull(idx) ?: return@setItems
                    Extras.grantTemp(m)
                    h.toast(h.str(R.string.set_temp_granted, m))
                }
                h.refresh()
            }
            .setNegativeButton(h.str(R.string.common_cancel), null)
            .show()
    }

    // ---------------- 记录备份（OPT-09） ----------------

    /**
     * 打卡与播放进度存在 NAS 的 `state.json`。
     * 平时由 App 自己备份（退到后台 / 每 5 分钟 / 每日首次打卡），这里只给家长
     * 一个「现在就备份一次」和「从 NAS 恢复」的入口。
     */
    private fun showStateBackup() {
        val days = Extras.stamps().size
        // 别改回 setItems：这条和 setMessage 同用会把操作项挤没（见 SettingsDialogs.actionDialog）
        h.act.actionDialog(
            h.str(R.string.set_state_title, days),
            h.str(R.string.set_state_msg, StateBackup.lastSavedText(), StateBackup.statePath()),
            listOf(
                h.str(R.string.set_state_do_backup) to {
                    h.toast(h.str(R.string.set_state_backing_up))
                    StateBackup.exportAsync("manual") { r ->
                        h.toast(r.message, long = true)
                        if (!h.act.isFinishing) h.refresh()
                    }
                },
                h.str(R.string.set_state_do_restore) to { confirmRestore() }
            )
        )
    }

    private fun confirmRestore() {
        h.act.confirmDialog(
            h.str(R.string.set_restore_confirm),
            h.str(R.string.set_restore_msg)
        ) {
            h.toast(h.str(R.string.set_restoring))
            StateBackup.importAsync { r ->
                h.toast(r.message, long = true)
                if (!h.act.isFinishing) h.refresh()
            }
        }
    }

    // ---------------- 诊断日志（OPT-17） ----------------

    /**
     * 出问题时电视把线索写在这里，家长不用连电脑。
     *
     * 只显示本地最近几条（够判断「是不是一直在报同一个错」），
     * 归档在 NAS 上，需要看全的可以去电脑上开 `data:curated/logs/`。
     */
    private fun showDiag() {
        val msg = buildString {
            append(h.str(R.string.set_diag_intro))
            append(h.str(R.string.set_diag_pending, Diag.pending()))
            append(h.str(R.string.set_diag_archive, Diag.logDirPath()))
            append(h.str(R.string.set_diag_keep))
            append(h.str(R.string.set_diag_recent)).append(Diag.recentText())
        }
        // 别改回 setItems：这条和 setMessage 同用会把操作项挤没（见 SettingsDialogs.actionDialog）
        h.act.actionDialog(
            h.str(R.string.set_diag),
            msg,
            listOf(
                h.str(R.string.set_diag_upload_now) to {
                    h.toast(h.str(R.string.set_diag_uploading))
                    Diag.uploadAsync { r ->
                        h.toast(r.message, long = true)
                        if (!h.act.isFinishing) h.refresh()
                    }
                },
                h.str(R.string.set_diag_clear) to {
                    h.act.confirmDialog(
                        h.str(R.string.set_diag_clear_confirm),
                        h.str(R.string.set_diag_clear_msg)
                    ) {
                        Diag.clear()
                        h.toast(h.str(R.string.set_diag_cleared))
                        h.refresh()
                    }
                }
            )
        )
    }

    // ---------------- 重载片单 ----------------

    private fun reloadPlaylist() {
        if (!h.beginTask(h.str(R.string.set_reloading))) return
        Thread {
            Smb.reset()
            val res = Repo.load()
            val ok = res.error == null
            PlaylistSync.applyToLocal(res.playlist)
            h.act.runOnUiThread {
                h.endTask()
                h.toast(h.str(if (ok) R.string.set_reloaded else R.string.set_reload_failed))
                h.refresh()
            }
        }.start()
    }
}
