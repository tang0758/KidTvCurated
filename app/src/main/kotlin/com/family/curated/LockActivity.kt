package com.family.curated

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import com.family.curated.data.ArtImages
import com.family.curated.data.CardType
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.data.KioskKeys
import com.family.curated.data.Learning
import com.family.curated.data.LearningCard
import com.family.curated.data.Store
import com.family.curated.databinding.ActivityLockBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 不可用时段的大倒计时锁屏：
 * 左边继续显示可用时段 + 大倒计时，右边是「等待时间学习角」（唐诗 / 英语单词 / 名画 / 识字）。
 * 孩子按什么键都退不出去，只有家长 PIN 能临时放行；方向键只用来翻学习卡片。
 */
class LockActivity : AppCompatActivity() {

    private lateinit var b: ActivityLockBinding
    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    private var learning: Learning = Learning()
    private var deck: List<LearningCard> = emptyList()
    private var idx = 0
    private var leftSeconds = 20
    private var paused = false

    private val tick = object : Runnable {
        override fun run() {
            render()
            if (!isFinishing && learning.enabled && deck.isNotEmpty() && !paused) {
                leftSeconds--
                if (leftSeconds <= 0) nextCard() else updateCardTimer()
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Extras.init(this)
        b = ActivityLockBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        // 倒计时可能被写成「1天 02:03:04」，分栏后宽度变窄，让系统自动缩字号
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            b.tvCountdown, 26, 68, 2, TypedValue.COMPLEX_UNIT_SP
        )
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(tick)
        paused = false
        leftSeconds = learning.intervalSeconds
        prepareCards()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    // ---------------- 左：倒计时 ----------------

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

    // ---------------- 右：学习角 ----------------

    private fun prepareCards() {
        learning = Extras.learning()
        if (!learning.enabled) {
            b.learningPane.visibility = View.GONE
            b.divider.visibility = View.GONE
            return
        }
        b.learningPane.visibility = View.VISIBLE
        b.divider.visibility = View.VISIBLE

        val base = learning.deck()
        if (base.isEmpty()) {
            // 家长关掉内置又没写自定义卡片：右侧留一句提示，不显示空白
            b.learningPane.visibility = View.GONE
            b.divider.visibility = View.GONE
            return
        }
        deck = base
        if (idx >= deck.size) idx = 0
        leftSeconds = learning.intervalSeconds
        showCard()

        // 配了 NAS 名画目录就在后台把图片贴上去，贴好再刷新当前卡片
        if (learning.artFolder.isNotBlank()) {
            val cfg = Store.nas()
            val snapshot = learning
            Thread {
                val bound = ArtImages.bindNasArt(base, snapshot, cfg)
                runOnUiThread {
                    if (!isFinishing && bound.isNotEmpty()) {
                        deck = bound
                        showCard()
                    }
                }
            }.start()
        }
    }

    private fun nextCard() {
        if (deck.isEmpty()) return
        idx = (idx + 1) % deck.size
        leftSeconds = learning.intervalSeconds
        showCard()
    }

    private fun prevCard() {
        if (deck.isEmpty()) return
        idx = (idx - 1 + deck.size) % deck.size
        leftSeconds = learning.intervalSeconds
        showCard()
    }

    private fun updateCardTimer() {
        b.tvCardTimer.text = if (paused) "暂停" else leftSeconds.toString() + "s"
        b.cardProgress.progress =
            (leftSeconds * 100 / learning.intervalSeconds.coerceAtLeast(1)).coerceIn(0, 100)
        b.tvCardHint.text = if (paused)
            "已暂停 · 按播放键继续 · ◀ ▶ 换一张"
        else "遥控器 ◀ ▶ 换一张 · 播放键暂停"
    }

    private fun showCard() {
        val c = deck.getOrNull(idx) ?: return
        b.tvCardType.text = c.type.label
        b.tvCardIndex.text = "${idx + 1} / ${deck.size}"
        updateCardTimer()

        b.cardBody.animate().cancel()
        b.cardBody.alpha = 0.35f
        b.cardBody.animate().alpha(1f).setDuration(260).start()

        if (c.type == CardType.ART) showArt(c) else showText(c)
    }

    private fun showText(c: LearningCard) {
        b.textFrame.visibility = View.VISIBLE
        b.artFrame.visibility = View.GONE

        b.tvCardTitle.text = c.title
        b.tvCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, when (c.type) {
            CardType.WORD -> 56f
            CardType.TEXT -> when {
                c.title.length <= 1 -> 76f
                c.title.length <= 4 -> 50f
                c.title.length <= 7 -> 36f
                else -> 29f
            }
            CardType.POEM -> 36f
            else -> 34f
        })
        b.tvCardSub.text = c.sub
        b.tvCardSub.visibility = if (c.sub.isBlank()) View.GONE else View.VISIBLE
        b.tvCardNote.text = c.note
        b.tvCardNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)

        val maxLen = c.lines.maxOfOrNull { it.length } ?: 1
        val lineSize = when (c.type) {
            CardType.POEM -> (500f / maxLen.coerceAtLeast(1)).coerceIn(22f, 33f)
            CardType.WORD -> 24f
            CardType.TEXT -> 23f
            else -> 22f
        }
        b.linesHost.removeAllViews()
        c.lines.forEach { line ->
            val tv = TextView(this).apply {
                text = line
                gravity = Gravity.CENTER
                setTextColor(
                    when (c.type) {
                        CardType.POEM -> Color.parseColor("#F2F4F8")
                        CardType.WORD -> Color.parseColor("#D7E2F5")
                        else -> Color.parseColor("#AFC3E6")
                    }
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, lineSize)
                setLineSpacing(8f, 1f)
            }
            b.linesHost.addView(tv)
        }
    }

    private fun showArt(c: LearningCard) {
        b.textFrame.visibility = View.GONE
        b.artFrame.visibility = View.VISIBLE
        b.ivArt.setImageDrawable(null)
        b.tvArtPlaceholder.visibility = View.VISIBLE
        b.tvArtPlaceholder.text = "名画图片加载中…"
        b.tvCardNote.text = buildString {
            append("《").append(c.title.trim('《', '》')).append("》")
            if (c.sub.isNotBlank()) append(" · ").append(c.sub)
            if (c.note.isNotBlank()) append("\n").append(c.note)
        }
        b.tvCardNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)

        val w = b.cardBody.width.takeIf { it > 0 } ?: 800
        val h = b.cardBody.height.takeIf { it > 0 } ?: 480
        val image = c.image
        ArtImages.load(this, b.ivArt, c, Store.nas(), learning.artShare, w, h) { bmp ->
            if (b.ivArt.tag != image) return@load
            if (bmp != null) {
                b.ivArt.setImageBitmap(bmp)
                b.tvArtPlaceholder.visibility = View.GONE
            } else {
                b.tvArtPlaceholder.text =
                    "《${c.title}》\n把图片放到 NAS 的 artFolder 目录就能看到画"
            }
        }
    }

    // ---------------- 遥控器 ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KioskKeys.systemBlocked(keyCode)) return true
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
        if (!learning.enabled || deck.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                nextCard(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                prevCard(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_SPACE -> {
                paused = !paused
                leftSeconds = learning.intervalSeconds
                updateCardTimer()
                Toast.makeText(this, if (paused) "学习卡片已暂停" else "继续自动换卡片",
                    Toast.LENGTH_SHORT).show()
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
