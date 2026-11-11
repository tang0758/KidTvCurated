package com.family.curated

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.curated.data.AppCatalog
import com.family.curated.data.Extras
import com.family.curated.data.PlaylistWriter
import com.family.curated.data.Store

/**
 * 家长那一侧的几个共用动作：输密码、勾允许的应用、退出到桌面。
 *
 * 主界面的家长菜单和「家长控制」页面都用这一套，行为保持一致，
 * 免得两个入口各写一份、改了一边忘另一边。
 */
object ParentTools {

    /** 冲突提示 30 秒内只弹一次：清单每 15 秒轮询一次，别把家长烦死 */
    @Volatile private var lastConflictDialogAt = 0L

    /**
     * OPT-07：电视上有一次「没写回 NAS」的修改时，明确告诉家长，并给三条路。
     * 主界面（清单同步发现冲突时）和设置页（一进来）都会调，靠时间节流去重。
     */
    fun showUnsyncedConflict(
        activity: AppCompatActivity,
        force: Boolean = false,
        onResolved: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed || !Store.hasUnsyncedEdit) return
        if (!force) {
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (now - lastConflictDialogAt < 30_000L) return
                lastConflictDialogAt = now
            }
        }
        val canReplay = Store.pendingPlaylistJson != null
        val msg = buildString {
            append(activity.getString(R.string.pt_unsynced_head, Store.unsyncedLabel))
            append(Store.unsyncedMessage.ifBlank { activity.getString(R.string.word_write_failed) })
            append(activity.getString(R.string.pt_unsynced_mid))
            append(
                if (canReplay) activity.getString(R.string.pt_unsynced_ask)
                else activity.getString(R.string.pt_unsynced_no_data)
            )
        }
        val b = AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(activity.getString(R.string.pt_unsynced_title))
            .setMessage(msg)
            .setNeutralButton(activity.getString(R.string.pt_later), null)
        if (canReplay) {
            b.setPositiveButton(activity.getString(R.string.pt_overwrite)) { _, _ -> retryPending(activity, onResolved) }
            b.setNegativeButton(activity.getString(R.string.pt_discard)) { _, _ ->
                PlaylistWriter.discardPending()
                Toast.makeText(activity, activity.getString(R.string.pt_discarded), Toast.LENGTH_SHORT).show()
                onResolved()
            }
        } else {
            b.setNegativeButton(activity.getString(R.string.pt_discard)) { _, _ ->
                PlaylistWriter.discardPending()
                onResolved()
            }
        }
        try {
            b.show()
        } catch (e: Exception) {
            // 界面已经退到后台时弹窗会抛 BadToken，忽略即可，红色错误条仍在
        }
    }

    private fun retryPending(activity: AppCompatActivity, onResolved: () -> Unit) {
        Thread {
            val r = PlaylistWriter.restorePending()
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    if (r.ok) activity.getString(R.string.pt_resaved) else r.message,
                    Toast.LENGTH_LONG
                ).show()
                onResolved()
            }
        }.start()
    }

    /**
     * 电视上 EditText 一拿到焦点，方向键就移不出去（只能靠 TAB，遥控器一般没有）。
     * 所以让「回车 / OK」直接等于点确定：家长输完密码按一下就好。
     */
    fun submitOnEnter(input: EditText, dialog: AlertDialog) {
        val ok: () -> Boolean = {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
            true
        }
        input.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                ev?.keyCode == KeyEvent.KEYCODE_ENTER
            ) ok() else false
        }
        input.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN &&
                (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
            ) ok() else false
        }
    }

    /** 弹 PIN 输入框；密码对才执行 [onOk] */
    fun askPin(
        activity: AppCompatActivity,
        title: String? = null,
        onOk: () -> Unit
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = activity.getString(R.string.pt_pin_hint)
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title ?: activity.getString(R.string.pt_pin_title_default))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.common_confirm)) { _, _ ->
                if (input.text.toString() == Extras.pin()) onOk()
                else Toast.makeText(activity, activity.getString(R.string.word_wrong_pin), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.common_cancel), null)
            .create()
        dialog.show()
        submitOnEnter(input, dialog)
    }

    /** 从电视上已安装的应用里勾选孩子能看到的入口（桌面 / 设置 / 安装器等已过滤） */
    fun pickApps(activity: AppCompatActivity, onChanged: () -> Unit) {
        val catalog = AppCatalog.installed(activity)
        if (catalog.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.pt_no_apps), Toast.LENGTH_LONG).show()
            return
        }
        val selected = Extras.visibleApps()
        val labels = catalog.map { "${it.label}  (${it.spec})" }.toTypedArray()
        val checked = BooleanArray(catalog.size) { catalog[it].spec in selected }

        AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(activity.getString(R.string.pt_apps_title))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(activity.getString(R.string.common_save)) { _, _ ->
                val set = catalog.filterIndexed { i, _ -> checked[i] }.map { it.spec }.toSet()
                Extras.saveSelectedApps(set)
                onChanged()
                Toast.makeText(activity, activity.getString(R.string.pt_apps_shown, set.size), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(activity.getString(R.string.pt_hide_all)) { _, _ ->
                Extras.saveSelectedApps(emptySet())
                onChanged()
                Toast.makeText(activity, activity.getString(R.string.pt_apps_hidden), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.common_cancel), null)
            .show()
    }

    /**
     * 家长出口：先开一段临时放行，再显式启动别的桌面。
     * 不放行的话前台守卫会立刻把家长拉回来，等于把自己也锁在外面。
     */
    fun exitToLauncher(activity: AppCompatActivity) {
        val k = Extras.kiosk()
        val configured = ComponentName.unflattenFromString(k.exitComponent)
        if (configured != null) {
            if (launchComponent(activity, configured, k.exitMinutes)) return
        }

        val pm = activity.packageManager
        val candidates = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).filter { it.activityInfo.packageName != activity.packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }

        if (candidates.isEmpty()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.pt_no_launcher),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = candidates.map {
            it.loadLabel(pm).toString() + "  (" + it.activityInfo.packageName + ")"
        }.toTypedArray()
        AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(activity.getString(R.string.pt_launcher_title))
            .setItems(labels) { _, which ->
                val ai = candidates[which].activityInfo
                if (!launchComponent(activity, ComponentName(ai.packageName, ai.name), k.exitMinutes)) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.pt_launch_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(activity.getString(R.string.common_cancel), null)
            .show()
    }

    private fun launchComponent(
        activity: AppCompatActivity,
        cmp: ComponentName,
        minutes: Int
    ): Boolean = try {
        Extras.grantKioskExit(minutes)
        activity.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cmp)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Toast.makeText(
            activity, activity.getString(R.string.pt_temp_granted, minutes),
            Toast.LENGTH_LONG
        ).show()
        true
    } catch (e: Exception) {
        Extras.clearKioskExit()
        false
    }
}
