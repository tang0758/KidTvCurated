package com.family.curated.settings

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.family.curated.R

/**
 * 设置页弹窗的三个通用形状：单列表格、一行输入、二次确认。
 *
 * 电视上这几个框的样式必须一致（深色主题、20sp、输入框留白），
 * 以前在设置页里手抄了四五份，改一次颜色要翻五处 —— 现在只有这里一份。
 */

fun AppCompatActivity.darkDialog(): AlertDialog.Builder =
    AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)

/**
 * 电视遥控器只能靠方向键，输入框的统一版式：
 * 字大、留白足、深色底上浅灰提示字，[initial] 非空时把光标放到末尾（家长常要改末尾几位）。
 */
fun AppCompatActivity.inputField(
    hint: String,
    initial: String = "",
    type: Int = InputType.TYPE_CLASS_TEXT
): EditText {
    val input = EditText(this)
    input.inputType = type
    input.hint = hint
    if (initial.isNotEmpty()) {
        input.setText(initial)
        input.setSelection(input.text.length)
    }
    input.setTextColor(0xFFF2F4F8.toInt())
    input.setHintTextColor(0xFF7C8797.toInt())
    input.textSize = 20f
    input.setPadding(40, 24, 40, 24)
    return input
}

/** 「确定 / 取消」两键，确定后执行 [onOk] */
fun AppCompatActivity.confirmDialog(title: String, message: String, onOk: () -> Unit) {
    darkDialog()
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(getString(R.string.common_confirm)) { _, _ -> onOk() }
        .setNegativeButton(getString(R.string.common_cancel), null)
        .show()
}

/** 单选列表，[current] 那一项前面打勾；行首的对齐空白让勾不出现时文字也不跳 */
fun AppCompatActivity.pickDialog(
    title: String,
    labels: List<String>,
    current: Int,
    onPick: (Int) -> Unit
) {
    val shown = labels.mapIndexed { i, s -> (if (i == current) "✓ " else "   ") + s }
    darkDialog()
        .setTitle(title)
        .setItems(shown.toTypedArray()) { _, which -> onPick(which) }
        .setNegativeButton(getString(R.string.common_cancel), null)
        .show()
}

/**
 * 「说明文字 + 若干可操作按钮」的对话框，电视专用。
 *
 * 2026-11-11 实机发现：`setMessage()` 和 `setItems()` 同时用时，
 * `AlertDialog.THEME_DEVICE_DEFAULT_DARK` 下 message 的 ScrollView 会把 items 的 ListView 挤没，
 * 「记录备份」「诊断日志」四个操作项完全看不见、遥控器也聚焦不到
 * （见 `docs/待实机验证.md` 5.2）。只有 `setItems` 的列表框则一直正常。
 *
 * 这里改成 `setView()`：说明放在固定高度、可内部滚动的 TextView 里，动作是竖排 Button。
 * Button 本身可聚焦、按下/聚焦有高亮，不再受消息长度和主题测量影响。
 * 文案仍全部来自 `strings.xml`，这个函数里不写死一个字。
 *
 * 动作超过 6 个时（主界面「家长选项」有 11 项）把按钮区也放进 ScrollView：
 * 原先用 `setItems` 的 ListView 在这台电视上方向键只能走到第 8 项，
 * 「临时放行」「退出到桌面」滑动才看得见、遥控器够不到。
 * Button 天生可聚焦，ScrollView 会跟着焦点滚，每一项都能走到。
 */
fun AppCompatActivity.actionDialog(
    title: String,
    message: CharSequence,
    actions: List<Pair<String, () -> Unit>>,
    scrollActions: Boolean = actions.size > 6
) {
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    // 每个按钮一份 StateListDrawable：共用同一实例会让所有按钮一起亮
    fun buttonBg(): StateListDrawable {
        fun box(fill: Int, stroke: Int) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(2), stroke)
        }
        return StateListDrawable().apply {
            val on = box(0xFF2F62C8.toInt(), 0xFF9EC2FF.toInt())
            addState(intArrayOf(android.R.attr.state_focused), on)
            addState(intArrayOf(android.R.attr.state_pressed), on)
            addState(intArrayOf(), box(0xFF2A3140.toInt(), 0xFF46536A.toInt()))
        }
    }

    lateinit var dialog: AlertDialog
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(12))
    }

    if (message.isNotEmpty()) {
        val body = TextView(this).apply {
            text = message
            setTextColor(0xFFE6ECF5.toInt())
            textSize = 16f
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        root.addView(
            ScrollView(this).apply {
                isFocusable = false
                setPadding(dp(4), dp(4), dp(4), dp(12))
                addView(
                    body,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(if (scrollActions) 80 else 190)
            )
        )
    }

    var first: Button? = null
    val actionHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun addButton(label: String, run: () -> Unit, host: LinearLayout = actionHost) {
        val b = Button(this).apply {
            text = label
            setTextColor(0xFFF2F4F8.toInt())
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = false
            background = buttonBg()
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                dialog.dismiss()
                run()
            }
        }
        host.addView(
            b,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        if (first == null) first = b
    }

    actions.forEach { (label, run) -> addButton(label, run) }
    if (scrollActions) {
        root.addView(
            ScrollView(this).apply {
                isFocusable = false
                addView(
                    actionHost,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(330))
        )
    } else {
        root.addView(
            actionHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }
    // 「关闭」留在滚动区外面，滚到哪都能直接按到
    addButton(getString(R.string.common_close), { }, root)

    dialog = darkDialog()
        .setTitle(title)
        .setView(root)
        .setCancelable(true)
        .create()
    dialog.show()
    // show() 之后系统可能重新分配焦点，post 一帧把焦点交给第一个动作
    root.post { first?.requestFocus() }
}
