package com.family.curated

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.databinding.ActivityLockBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 不可用时段的大倒计时锁屏。孩子按什么键都退不出去，只有家长 PIN 能临时放行。 */
class LockActivity : AppCompatActivity() {

    private lateinit var b: ActivityLockBinding
    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Extras.init(this)
        b = ActivityLockBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun render() {
        val st = GateKeeper.status()
        if (st.open) {
            finish()
            return
        }
        b.tvHeadline.text = st.headline
        b.tvCountdown.text = GateKeeper.hms(st.secondsLeft)
        b.tvDetail.text = st.detail
        b.tvUsed.text = if (st.limitMinutes > 0)
            "今天已看 ${st.usedMinutes} / ${st.limitMinutes} 分钟"
        else "今天已看 ${st.usedMinutes} 分钟"
        b.tvNow.text = "现在 " + timeFmt.format(Date())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                showParentOverride()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> return true
            // 数字键直接当作 PIN 输入，省去对话框里找焦点
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                appendPinDigit(keyCode - KeyEvent.KEYCODE_0)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val pinBuffer = StringBuilder()

    private fun appendPinDigit(d: Int) {
        if (pinBuffer.length >= 6) pinBuffer.setLength(0)
        pinBuffer.append(d)
        if (pinBuffer.length >= 4) {
            val entered = pinBuffer.toString().takeLast(4)
            pinBuffer.setLength(0)
            if (entered == Extras.pin()) {
                Extras.grantTemp(15)
                Toast.makeText(this, "已临时放行 15 分钟", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "密码不对", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showParentOverride() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "输入家长密码"
            setTextColor(0xFFF2F4F8.toInt())
            setHintTextColor(0xFF7C8797.toInt())
            textSize = 20f
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("家长临时放行")
            .setMessage("输入密码后放行 15 分钟（密码在 tv.json 的 pin 字段）")
            .setView(input)
            .setPositiveButton("放行 15 分钟") { _, _ ->
                if (input.text.toString() == Extras.pin()) {
                    Extras.grantTemp(15)
                    Toast.makeText(this, "已临时放行 15 分钟", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "密码不对", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
