package com.family.curated

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.media.MediaPlayer
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import com.family.curated.data.ArtImages
import com.family.curated.data.CardMastery
import com.family.curated.data.CardType
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.data.KioskKeys
import com.family.curated.data.Learning
import com.family.curated.data.LearningCard
import com.family.curated.data.LearningMath
import com.family.curated.data.Store
import com.family.curated.databinding.ActivityLockBinding
import com.family.curated.ui.PinPadDialog
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

    private var dimmed = false
    private var lastInteractionAt = 0L
    private var swallowKeyUpCode: Int? = null
    private var swallowTouchUntilUp = false
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private val tick = object : Runnable {
        override fun run() {
            render()
            if (!isFinishing && learning.enabled && deck.isNotEmpty() && !paused) {
                leftSeconds--
                if (leftSeconds <= 0) nextCard() else updateCardTimer()
            }
            if (!isFinishing && !dimmed && hasWindowFocus() &&
                SystemClock.elapsedRealtime() - lastInteractionAt >= DIM_IDLE_TIMEOUT_MS
            ) {
                enterDimMode()
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
        originalBrightness = window.attributes.screenBrightness
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
        wakeFromDim(animate = false)
        prepareCards()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        swallowKeyUpCode = null
        swallowTouchUntilUp = false
        wakeFromDim(animate = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !dimmed) {
            lastInteractionAt = SystemClock.elapsedRealtime()
        }
    }

    private fun enterDimMode() {
        if (dimmed) return
        dimmed = true
        applyBrightness(true)
        b.dimOverlay.animate().cancel()
        b.dimOverlay.visibility = View.VISIBLE
        b.dimOverlay.animate().alpha(DIM_OVERLAY_ALPHA).setDuration(600).start()
    }

    private fun wakeFromDim(animate: Boolean = true) {
        lastInteractionAt = SystemClock.elapsedRealtime()
        if (!dimmed && b.dimOverlay.visibility != View.VISIBLE) return
        dimmed = false
        applyBrightness(false)
        b.dimOverlay.animate().cancel()
        if (!animate) {
            b.dimOverlay.alpha = 0f
            b.dimOverlay.visibility = View.GONE
            return
        }
        b.dimOverlay.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                if (!isFinishing && !dimmed) {
                    b.dimOverlay.visibility = View.GONE
                }
            }
            .start()
    }

    private fun applyBrightness(dim: Boolean) {
        val lp = window.attributes
        val target = if (dim) DIM_SCREEN_BRIGHTNESS else originalBrightness
        if (lp.screenBrightness != target) {
            lp.screenBrightness = target
            window.attributes = lp
        }
    }

    companion object {
        private const val DIM_IDLE_TIMEOUT_MS = 3L * 60_000L
        private const val DIM_OVERLAY_ALPHA = 0.45f
        private const val DIM_SCREEN_BRIGHTNESS = 0.14f
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
            getString(R.string.lock_time_of_limit, st.usedMinutes, st.limitMinutes)
        else getString(R.string.lock_time, st.usedMinutes)
        b.tvNow.text = getString(R.string.lock_now, timeFmt.format(Date()))
        val cfgVer = Extras.configVersion()
        if (cfgVer.isNotBlank()) {
            b.tvLockConfigVersion.text = getString(R.string.config_version_badge, cfgVer)
            b.tvLockConfigVersion.visibility = View.VISIBLE
        } else {
            b.tvLockConfigVersion.visibility = View.GONE
        }
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

        val base = learning.deck(Extras.allCardMasteries())
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
        b.tvCardTimer.text = if (paused) getString(R.string.lock_paused) else leftSeconds.toString() + "s"
        b.cardProgress.progress =
            (leftSeconds * 100 / learning.intervalSeconds.coerceAtLeast(1)).coerceIn(0, 100)
        b.tvCardHint.text = if (paused)
            getString(R.string.lock_hint_paused)
        else getString(R.string.lock_hint_v2)
    }

    private fun showCard() {
        val c = deck.getOrNull(idx) ?: return
        b.tvCardType.text = c.type.label
        b.tvCardIndex.text = "${idx + 1} / ${deck.size}"

        val cardId = LearningMath.cardId(c)
        when (Extras.cardMastery(cardId)) {
            CardMastery.MASTERED -> {
                b.tvCardMastery.visibility = View.VISIBLE
                b.tvCardMastery.text = "✅ " + getString(R.string.lock_mastered)
                b.tvCardMastery.setBackgroundColor(Color.parseColor("#2E7D32"))
            }
            CardMastery.LEARNING -> {
                b.tvCardMastery.visibility = View.VISIBLE
                b.tvCardMastery.text = "⭐ " + getString(R.string.lock_learning)
                b.tvCardMastery.setBackgroundColor(Color.parseColor("#F57F17"))
            }
            CardMastery.UNMARKED -> {
                b.tvCardMastery.visibility = View.GONE
            }
        }

        updateCardTimer()

        b.cardBody.animate().cancel()
        b.cardBody.alpha = 0.35f
        b.cardBody.animate().alpha(1f).setDuration(260).start()

        if (c.type == CardType.ART) showArt(c) else showText(c)
    }

    private fun showText(c: LearningCard) {
        b.textFrame.visibility = View.VISIBLE
        b.artFrame.visibility = View.GONE

        if (!c.image.isNullOrBlank()) {
            ArtImages.load(this, b.ivCardIllust, c, Store.nas(), learning.artShare, 400, 400) { bmp ->
                if (bmp != null) {
                    b.ivCardIllust.setImageBitmap(bmp)
                    b.ivCardIllust.visibility = View.VISIBLE
                } else {
                    b.ivCardIllust.visibility = View.GONE
                }
            }
        } else {
            b.ivCardIllust.setImageDrawable(null)
            b.ivCardIllust.visibility = View.GONE
        }

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
        b.tvArtPlaceholder.text = getString(R.string.lock_art_loading)
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
                    getString(R.string.lock_art_missing, c.title)
            }
        }
    }

    // ---------------- 遥控器 ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (KioskKeys.systemBlocked(code)) return true
        val isVolumeKey = code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_VOLUME_MUTE
        if (!isVolumeKey) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (dimmed) {
                        swallowKeyUpCode = code
                        wakeFromDim()
                        return true
                    }
                    if (swallowKeyUpCode == code) return true
                    lastInteractionAt = SystemClock.elapsedRealtime()
                }
                KeyEvent.ACTION_UP -> {
                    if (swallowKeyUpCode == code) {
                        swallowKeyUpCode = null
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dimmed) {
                    swallowTouchUntilUp = true
                    wakeFromDim()
                    return true
                }
                lastInteractionAt = SystemClock.elapsedRealtime()
            }
            MotionEvent.ACTION_MOVE -> if (swallowTouchUntilUp) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swallowTouchUntilUp) {
                    swallowTouchUntilUp = false
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

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
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                nextCard(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                prevCard(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val c = deck.getOrNull(idx)
                if (c != null) {
                    val cid = LearningMath.cardId(c)
                    Extras.setCardMastery(cid, CardMastery.MASTERED)
                    Toast.makeText(this, getString(R.string.lock_marked_mastered), Toast.LENGTH_SHORT).show()
                    showCard()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val c = deck.getOrNull(idx)
                if (c != null) {
                    val cid = LearningMath.cardId(c)
                    val cur = Extras.cardMastery(cid)
                    val target = if (cur == CardMastery.LEARNING) CardMastery.UNMARKED else CardMastery.LEARNING
                    Extras.setCardMastery(cid, target)
                    Toast.makeText(this, getString(R.string.lock_marked_learning), Toast.LENGTH_SHORT).show()
                    showCard()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val c = deck.getOrNull(idx)
                if (c != null) {
                    if (!c.audio.isNullOrBlank()) {
                        playCardAudio(c)
                    } else {
                        Toast.makeText(this, getString(R.string.lock_no_audio), Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_SPACE -> {
                paused = !paused
                leftSeconds = learning.intervalSeconds
                updateCardTimer()
                Toast.makeText(
                    this,
                    if (paused) getString(R.string.lock_cards_paused) else getString(R.string.lock_cards_resumed),
                    Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var cardAudioPlayer: MediaPlayer? = null

    private fun playCardAudio(c: LearningCard) {
        val audioPath = c.audio?.takeIf { it.isNotBlank() } ?: run {
            Toast.makeText(this, getString(R.string.lock_no_audio), Toast.LENGTH_SHORT).show()
            return
        }
        val source = if (audioPath.startsWith("http://") || audioPath.startsWith("https://")) {
            audioPath
        } else {
            val localF = java.io.File(filesDir, "audio/" + audioPath.substringAfterLast("/"))
            if (!localF.exists()) {
                Toast.makeText(this, getString(R.string.lock_no_audio), Toast.LENGTH_SHORT).show()
                return
            }
            localF.absolutePath
        }
        try {
            cardAudioPlayer?.release()
            cardAudioPlayer = MediaPlayer().apply {
                setDataSource(source)
                setOnCompletionListener {
                    paused = false
                    updateCardTimer()
                }
                prepare()
                start()
                paused = true
                updateCardTimer()
            }
        } catch (_: Exception) {
            cardAudioPlayer?.release()
            cardAudioPlayer = null
            paused = false
            updateCardTimer()
            Toast.makeText(this, getString(R.string.lock_no_audio), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cardAudioPlayer?.release()
        cardAudioPlayer = null
    }

    private val pinBuffer = StringBuilder()

    private fun appendPinDigit(d: Int) {
        val targetPin = Extras.pin().ifBlank { "1234" }
        val targetLen = targetPin.length.coerceIn(4, 8)
        if (pinBuffer.length >= 8) pinBuffer.setLength(0)
        pinBuffer.append(d)
        if (pinBuffer.length >= targetLen) {
            val entered = pinBuffer.toString().takeLast(targetLen)
            pinBuffer.setLength(0)
            if (entered == targetPin) {
                Extras.grantTemp(15)
                Toast.makeText(this, getString(R.string.lock_granted_15), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, getString(R.string.word_wrong_pin), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showParentOverride() {
        PinPadDialog.showVerify(
            context = this,
            title = getString(R.string.lock_pin_title),
            message = getString(R.string.lock_pin_msg),
            confirmLabel = getString(R.string.temp_15)
        ) {
            Extras.grantTemp(15)
            Toast.makeText(this, getString(R.string.lock_granted_15), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
