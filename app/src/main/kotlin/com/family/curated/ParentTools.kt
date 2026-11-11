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
            append("上次在电视上改「${Store.unsyncedLabel}」时没能写进 NAS：\n")
            append(Store.unsyncedMessage.ifBlank { "写入失败" })
            append("\n\nNAS 上仍是你上次保存的内容，电视本机也没有改，两边都没有偷偷变。\n\n")
            append(
                if (canReplay) "要现在用电视上的设置覆盖 NAS 吗？"
                else "当时连 NAS 都没读到，没有可重放的内容，只能放弃后重新设置一次。"
            )
        }
        val b = AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("有设置没同步到 NAS")
            .setMessage(msg)
            .setNeutralButton("稍后再说", null)
        if (canReplay) {
            b.setPositiveButton("覆盖 NAS") { _, _ -> retryPending(activity, onResolved) }
            b.setNegativeButton("放弃这次修改") { _, _ ->
                PlaylistWriter.discardPending()
                Toast.makeText(activity, "已放弃，继续用 NAS 上的设置", Toast.LENGTH_SHORT).show()
                onResolved()
            }
        } else {
            b.setNegativeButton("放弃这次修改") { _, _ ->
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
        UiAsync.run(
            activity,
            io = { PlaylistWriter.restorePending() },
            onMain = { r ->
                Toast.makeText(
                    activity,
                    if (r.ok) "已重新保存到 NAS" else r.message,
                    Toast.LENGTH_LONG
                ).show()
                onResolved()
            }
        )
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
        title: String = "家长选项",
        onOk: () -> Unit
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "家长密码（tv.json 的 pin）"
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        val dialog = AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                if (input.text.toString() == Extras.pin()) onOk()
                else Toast.makeText(activity, "密码不对", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        submitOnEnter(input, dialog)
    }

    /** 从电视上已安装的应用里勾选孩子能看到的入口（桌面 / 设置 / 安装器等已过滤） */
    fun pickApps(activity: AppCompatActivity, onChanged: () -> Unit) {
        val catalog = AppCatalog.installed(activity)
        if (catalog.isEmpty()) {
            Toast.makeText(activity, "没找到可以启动的应用", Toast.LENGTH_LONG).show()
            return
        }
        val selected = Extras.visibleApps()
        val labels = catalog.map { "${it.label}  (${it.spec})" }.toTypedArray()
        val checked = BooleanArray(catalog.size) { catalog[it].spec in selected }

        AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("选择孩子能看到的 App（OK 勾选）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val set = catalog.filterIndexed { i, _ -> checked[i] }.map { it.spec }.toSet()
                Extras.saveSelectedApps(set)
                onChanged()
                Toast.makeText(activity, "已显示 ${set.size} 个应用入口", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("全部隐藏") { _, _ ->
                Extras.saveSelectedApps(emptySet())
                onChanged()
                Toast.makeText(activity, "已隐藏所有应用入口", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
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
                "没找到其它桌面，请在 kiosk.exitComponent 里指定",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = candidates.map {
            it.loadLabel(pm).toString() + "  (" + it.activityInfo.packageName + ")"
        }.toTypedArray()
        AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("退出到哪个桌面？")
            .setItems(labels) { _, which ->
                val ai = candidates[which].activityInfo
                if (!launchComponent(activity, ComponentName(ai.packageName, ai.name), k.exitMinutes)) {
                    Toast.makeText(
                        activity,
                        "启动失败，请把组件名写进 kiosk.exitComponent",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
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
            activity, "已临时放行 $minutes 分钟，回来后可在家长控制里结束放行",
            Toast.LENGTH_LONG
        ).show()
        true
    } catch (e: Exception) {
        Extras.clearKioskExit()
        false
    }
}
