package com.family.curated.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.family.curated.R
import com.family.curated.data.Extras
import com.family.curated.databinding.DialogPinPadBinding

/**
 * 电视端纯虚拟数字键盘弹窗（不拉起系统输入法软键盘），统一暗色+金边 TV 风格。
 * 同时支持遥控器方向键点按虚拟键，以及物理数字键 / 退格键直输。
 */
object PinPadDialog {

    private const val MAX_LEN = 8

    /** 校验家长 PIN；密码正确关闭弹窗并执行 [onVerified]，否则清空输入继续重试。 */
    fun showVerify(
        context: Context,
        title: String,
        message: String? = null,
        confirmLabel: String? = null,
        expectedPin: () -> String = { Extras.pin().ifBlank { "1234" } },
        onVerified: () -> Unit
    ) {
        show(
            context = context,
            title = title,
            message = message,
            confirmLabel = confirmLabel ?: context.getString(R.string.common_confirm),
            hintWhenEmpty = context.getString(R.string.pin_pad_empty_hint),
            maxLength = MAX_LEN
        ) { code, clearAndRefresh ->
            if (code == expectedPin()) {
                onVerified()
                true
            } else {
                Toast.makeText(context, context.getString(R.string.word_wrong_pin), Toast.LENGTH_SHORT).show()
                clearAndRefresh()
                false
            }
        }
    }

    /** 输入新数字（用于设置页改 PIN）；[onConfirm] 返回 true 表示通过并关闭，false 保持弹窗。 */
    fun showInput(
        context: Context,
        title: String,
        message: String? = null,
        hintWhenEmpty: String? = null,
        confirmLabel: String? = null,
        maxLength: Int = MAX_LEN,
        onConfirm: (code: String, clearAndRefresh: () -> Unit) -> Boolean
    ) {
        show(
            context = context,
            title = title,
            message = message,
            confirmLabel = confirmLabel ?: context.getString(R.string.common_save),
            hintWhenEmpty = hintWhenEmpty ?: context.getString(R.string.pin_pad_empty_hint),
            maxLength = maxLength,
            onConfirm = onConfirm
        )
    }

    private fun show(
        context: Context,
        title: String,
        message: String?,
        confirmLabel: String,
        hintWhenEmpty: String,
        maxLength: Int,
        onConfirm: (code: String, clearAndRefresh: () -> Unit) -> Boolean
    ) {
        val binding = DialogPinPadBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(binding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }

        binding.tvPinTitle.text = title
        if (message.isNullOrBlank()) {
            binding.tvPinMessage.visibility = View.GONE
        } else {
            binding.tvPinMessage.visibility = View.VISIBLE
            binding.tvPinMessage.text = message
        }
        binding.btnPinConfirm.text = confirmLabel

        val digits = StringBuilder()
        fun refreshDisplay() {
            if (digits.isEmpty()) {
                binding.tvPinDisplay.text = "—  —  —  —"
                binding.tvPinDisplay.setTextColor(0xFF5F6875.toInt())
                binding.tvPinHint.text = hintWhenEmpty
            } else {
                binding.tvPinDisplay.text = List(digits.length) { "●" }.joinToString("  ")
                binding.tvPinDisplay.setTextColor(0xFFF2F4F8.toInt())
                binding.tvPinHint.text = context.getString(R.string.pin_pad_count, digits.length)
            }
        }

        fun appendDigit(d: Char) {
            if (digits.length >= maxLength) return
            digits.append(d)
            refreshDisplay()
        }

        fun backspace() {
            if (digits.isEmpty()) return
            digits.deleteCharAt(digits.length - 1)
            refreshDisplay()
        }

        fun clearAll() {
            if (digits.isEmpty()) return
            digits.clear()
            refreshDisplay()
        }

        val digitButtons: List<Pair<TextView, Char>> = listOf(
            binding.btnKey0 to '0',
            binding.btnKey1 to '1',
            binding.btnKey2 to '2',
            binding.btnKey3 to '3',
            binding.btnKey4 to '4',
            binding.btnKey5 to '5',
            binding.btnKey6 to '6',
            binding.btnKey7 to '7',
            binding.btnKey8 to '8',
            binding.btnKey9 to '9'
        )
        digitButtons.forEach { (btn, digit) ->
            btn.setOnClickListener { appendDigit(digit) }
        }
        binding.btnKeyBack.setOnClickListener { backspace() }
        binding.btnKeyClear.setOnClickListener { clearAll() }
        binding.btnPinCancel.setOnClickListener { dialog.dismiss() }
        binding.btnPinConfirm.setOnClickListener {
            if (onConfirm(digits.toString(), ::clearAll)) {
                dialog.dismiss()
            }
        }

        val allButtons: List<View> = digitButtons.map { it.first } + listOf(
            binding.btnKeyBack,
            binding.btnKeyClear,
            binding.btnPinCancel,
            binding.btnPinConfirm
        )
        allButtons.forEach { v ->
            v.setOnFocusChangeListener { view, hasFocus ->
                val s = if (hasFocus) 1.04f else 1.0f
                view.animate().scaleX(s).scaleY(s).setDuration(100).start()
            }
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    appendDigit(('0'.code + (keyCode - KeyEvent.KEYCODE_0)).toChar())
                    true
                }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    appendDigit(('0'.code + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)).toChar())
                    true
                }
                KeyEvent.KEYCODE_DEL -> {
                    backspace()
                    true
                }
                else -> false
            }
        }

        refreshDisplay()
        dialog.show()
        binding.btnKey1.post { binding.btnKey1.requestFocus() }
    }
}
