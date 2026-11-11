package com.family.curated.settings

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
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
