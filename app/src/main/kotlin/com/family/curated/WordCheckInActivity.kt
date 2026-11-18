package com.family.curated

import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.curated.data.CardMastery
import com.family.curated.data.ClockGuard
import com.family.curated.data.Extras
import com.family.curated.data.KioskKeys
import com.family.curated.data.LearningCard
import com.family.curated.data.LearningLibrary
import com.family.curated.data.LearningMath
import com.family.curated.data.MediaGateway
import com.family.curated.data.Smb
import com.family.curated.data.StateBackup
import com.family.curated.data.Store
import com.family.curated.data.WordCheckInMath
import com.family.curated.databinding.ActivityWordCheckinBinding
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 每日英语单词打卡页（FR-35 / OPT-34）。
 * 严守规则：本页不启动 WatchClock，不计入看片时长账本。
 */
class WordCheckInActivity : AppCompatActivity() {

    private lateinit var b: ActivityWordCheckinBinding
    private val ioPool = Executors.newSingleThreadExecutor()

    private var queue: List<LearningCard> = emptyList()
    private var idx = 0
    private var imageRevealed = false

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Extras.init(this)
        b = ActivityWordCheckinBinding.inflate(layoutInflater)
        setContentView(b.root)

        initTts()
        loadTodayQueue()

        b.btnWordAudio.setOnClickListener { currentCard()?.let { playWordAudio(it) } }
        b.btnWordImage.setOnClickListener { toggleImage() }
        b.btnWordKnow.setOnClickListener { answerCurrent(WordCheckInMath.Action.KNOW) }
        b.btnWordUnknown.setOnClickListener { answerCurrent(WordCheckInMath.Action.UNKNOWN) }
        b.btnWordGraduate.setOnClickListener { answerCurrent(WordCheckInMath.Action.GRADUATE) }

        listOf(b.btnWordAudio, b.btnWordImage, b.btnWordKnow, b.btnWordUnknown, b.btnWordGraduate).forEach { btn ->
            btn.setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.07f else 1f).scaleY(if (has) 1.07f else 1f)
                    .setDuration(100).start()
            }
        }

        if (queue.isEmpty()) {
            Extras.markWordCheckIn(ClockGuard.now())
            Toast.makeText(this, getString(R.string.word_empty_toast), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        showCard()
        b.btnWordKnow.requestFocus()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.US) ?: TextToSpeech.LANG_MISSING_DATA
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun loadTodayQueue() {
        val (allWords, dailyCount) = LearningLibrary.loadOnlineWords(Store.cachedLearningJson)
        val dayKey = ClockGuard.day()
        val ids = Extras.ensureTodayWordQueue(allWords, dailyCount, dayKey)
        val byId = allWords.associateBy { LearningMath.cardId(it) }
        val resolved = ids.mapNotNull { byId[it] }
        val answered = Extras.wordAnswered(dayKey)
        queue = resolved
        val firstUnanswered = resolved.indexOfFirst { LearningMath.cardId(it) !in answered }
        idx = if (firstUnanswered >= 0) firstUnanswered else 0
    }

    private fun currentCard(): LearningCard? = queue.getOrNull(idx)

    private fun showCard() {
        val card = currentCard() ?: return
        imageRevealed = false
        b.imageBox.visibility = View.GONE
        b.ivWordImage.setImageDrawable(null)
        b.tvNoImage.visibility = View.GONE
        b.btnWordImage.text = getString(R.string.word_btn_image)

        b.tvWordProgress.text = getString(R.string.word_checkin_progress, idx + 1, queue.size)
        val mem = Extras.wordMemory(LearningMath.cardId(card))
        b.tvWordStage.text = when {
            mem.isGraduated -> getString(R.string.word_stage_graduated)
            mem.mastery == CardMastery.LEARNING -> getString(R.string.word_stage_learning)
            mem.stage > 0 -> getString(R.string.word_stage_review, mem.stage)
            else -> getString(R.string.word_stage_new)
        }
        b.tvWordTitle.text = card.title
        b.tvWordSub.text = card.sub
        b.tvWordSub.visibility = if (card.sub.isBlank()) View.GONE else View.VISIBLE
        b.tvWordNote.text = card.note
        b.tvWordNote.visibility = if (card.note.isBlank()) View.GONE else View.VISIBLE
        b.tvWordLines.text = card.lines.joinToString("\n")
        b.tvWordLines.visibility = if (card.lines.isEmpty()) View.GONE else View.VISIBLE

        playWordAudio(card)
    }

    private fun toggleImage() {
        val card = currentCard() ?: return
        imageRevealed = !imageRevealed
        if (!imageRevealed) {
            b.imageBox.visibility = View.GONE
            b.btnWordImage.text = getString(R.string.word_btn_image)
            return
        }
        b.imageBox.visibility = View.VISIBLE
        b.btnWordImage.text = getString(R.string.word_btn_image_hide)
        val imgPath = card.image?.trim().orEmpty()
        if (imgPath.isBlank()) {
            b.ivWordImage.setImageDrawable(null)
            b.tvNoImage.visibility = View.VISIBLE
            return
        }
        b.tvNoImage.visibility = View.GONE
        val expectedId = LearningMath.cardId(card)
        ioPool.execute {
            val bmp = loadBitmap(imgPath)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (currentCard()?.let { LearningMath.cardId(it) } != expectedId) return@runOnUiThread
                if (bmp != null) {
                    b.ivWordImage.setImageBitmap(bmp)
                    b.tvNoImage.visibility = View.GONE
                } else {
                    b.ivWordImage.setImageDrawable(null)
                    b.tvNoImage.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun loadBitmap(path: String): android.graphics.Bitmap? {
        try {
            if (path.startsWith("asset:")) {
                val rel = path.removePrefix("asset:")
                assets.open(rel).use { return BitmapFactory.decodeStream(it) }
            }
            val cfg = Store.nas() ?: return null
            val share = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE
            val bytes = Smb.readAllIsolated(cfg, share, path)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            return null
        }
    }

    private fun playWordAudio(card: LearningCard) {
        releasePlayer()
        val audioPath = card.audio?.trim().orEmpty()
        val speakText = card.ttsText.ifBlank { card.title }
        if (audioPath.isBlank()) {
            speakTts(speakText)
            return
        }
        ioPool.execute {
            val url = buildGatewayAudioUrl(audioPath)
            if (url == null) {
                runOnUiThread { speakTts(speakText) }
                return@execute
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    val mp = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(url)
                        setOnPreparedListener { it.start() }
                        setOnErrorListener { _, _, _ ->
                            speakTts(speakText)
                            true
                        }
                        prepareAsync()
                    }
                    mediaPlayer = mp
                } catch (_: Exception) {
                    speakTts(speakText)
                }
            }
        }
    }

    private fun buildGatewayAudioUrl(relPath: String): String? {
        val cfg = Store.nas() ?: return null
        if (!MediaGateway.isHttpAvailable(cfg)) return null
        val share = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE
        val encShare = Uri.encode(share)
        val encPath = relPath.trimStart('/').split('/').joinToString("/") { Uri.encode(it) }
        return "http://${cfg.host}:${cfg.gatewayPort}/stream/$encShare/$encPath"
    }

    private fun speakTts(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word_speak")
    }

    private fun answerCurrent(action: WordCheckInMath.Action) {
        val card = currentCard() ?: return
        val cid = LearningMath.cardId(card)
        val dayKey = ClockGuard.day()
        Extras.recordWordMemory(cid, action, dayKey)
        Extras.markWordAnswered(cid, dayKey)
        StateBackup.exportAsync("word_checkin") {}

        if (idx + 1 < queue.size) {
            idx++
            showCard()
        } else {
            Extras.markWordCheckIn(ClockGuard.now())
            Toast.makeText(this, getString(R.string.word_done_toast, queue.size), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KioskKeys.systemBlocked(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        releasePlayer()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        ioPool.shutdownNow()
        super.onDestroy()
    }
}
