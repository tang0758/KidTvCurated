package com.family.curated

import android.app.AlertDialog
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
import com.family.curated.data.RemoteCommand
import com.family.curated.data.Smb
import com.family.curated.data.StateBackup
import com.family.curated.data.Store
import com.family.curated.data.WordCheckInMath
import com.family.curated.databinding.ActivityWordCheckinBinding
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 每日打卡页（支持英语单词 / 汉字两种模式，通过 [EXTRA_MODE] 切换）。
 * 严守规则：本页不启动 WatchClock，不计入看片时长账本。
 */
class WordCheckInActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "checkin_mode"
        const val MODE_WORD = "word"
        const val MODE_HANZI = "hanzi"
    }

    private lateinit var b: ActivityWordCheckinBinding
    private val ioPool = Executors.newSingleThreadExecutor()

    private var isHanziMode = false
    private var queue: List<LearningCard> = emptyList()
    private var idx = 0
    private var imageRevealed = false

    /** 本次会话中每题作答前的记忆快照 & 本题选择结果，用于「上一词」回退改判和结算统计 */
    private val preAnswerSnapshots = HashMap<String, WordCheckInMath.MemoryState>()
    private val sessionChoices = HashMap<String, WordCheckInMath.Action>()

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Extras.init(this)
        isHanziMode = intent.getStringExtra(EXTRA_MODE) == MODE_HANZI
        b = ActivityWordCheckinBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (isHanziMode) {
            b.tvCheckInTitle.text = getString(R.string.hanzi_entry_title)
            b.btnWordPrev.text = getString(R.string.hanzi_btn_prev)
            b.tvSummaryTitle.text = getString(R.string.hanzi_summary_title)
            b.tvNoImage.text = getString(R.string.hanzi_no_image)
        }

        initTts()
        loadTodayQueue()

        b.btnWordPrev.setOnClickListener { goPrev() }
        b.btnWordAudio.setOnClickListener { currentCard()?.let { playWordAudio(it) } }
        b.btnWordImage.setOnClickListener { toggleImage() }
        b.btnWordKnow.setOnClickListener { answerCurrent(WordCheckInMath.Action.KNOW) }
        b.btnWordUnknown.setOnClickListener { answerCurrent(WordCheckInMath.Action.UNKNOWN) }
        b.btnWordGraduate.setOnClickListener { confirmGraduate() }
        b.btnSummaryDone.setOnClickListener { finish() }

        listOf(
            b.btnWordPrev, b.btnWordAudio, b.btnWordImage,
            b.btnWordKnow, b.btnWordUnknown, b.btnWordGraduate, b.btnSummaryDone
        ).forEach { btn ->
            btn.setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.035f else 1f).scaleY(if (has) 1.035f else 1f)
                    .setDuration(100).start()
            }
        }

        if (queue.isEmpty()) {
            if (isHanziMode) {
                Extras.markHanziCheckIn(ClockGuard.now())
                Toast.makeText(this, getString(R.string.hanzi_empty_toast), Toast.LENGTH_SHORT).show()
            } else {
                Extras.markWordCheckIn(ClockGuard.now())
                Toast.makeText(this, getString(R.string.word_empty_toast), Toast.LENGTH_SHORT).show()
            }
            finish()
            return
        }
        showCard()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val lang = if (isHanziMode) Locale.CHINA else Locale.US
                val res = tts?.setLanguage(lang) ?: TextToSpeech.LANG_MISSING_DATA
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    private fun loadTodayQueue() {
        val dayKey = ClockGuard.day()
        if (isHanziMode) {
            val allHanzi = LearningLibrary.loadHanziCards(Extras.learning())
            val ids = Extras.ensureTodayHanziQueue(allHanzi, WordCheckInMath.DEFAULT_DAILY_COUNT, dayKey)
            val byId = allHanzi.associateBy { LearningMath.cardId(it) }
            val resolved = ids.mapNotNull { byId[it] }
            val answered = Extras.hanziAnswered(dayKey)
            queue = resolved
            val firstUnanswered = resolved.indexOfFirst { LearningMath.cardId(it) !in answered }
            idx = if (firstUnanswered >= 0) firstUnanswered else 0
        } else {
            val (allWords, dailyCount) = LearningLibrary.loadOnlineWords(Store.cachedLearningJson)
            val ids = Extras.ensureTodayWordQueue(allWords, dailyCount, dayKey)
            val byId = allWords.associateBy { LearningMath.cardId(it) }
            val resolved = ids.mapNotNull { byId[it] }
            val answered = Extras.wordAnswered(dayKey)
            queue = resolved
            val firstUnanswered = resolved.indexOfFirst { LearningMath.cardId(it) !in answered }
            idx = if (firstUnanswered >= 0) firstUnanswered else 0
        }
    }

    private fun currentCard(): LearningCard? = queue.getOrNull(idx)

    private fun showCard() {
        val card = currentCard() ?: return
        imageRevealed = false
        b.imageBox.visibility = View.GONE
        b.ivWordImage.setImageDrawable(null)
        b.tvNoImage.visibility = View.GONE
        b.btnWordImage.text = getString(R.string.word_btn_image)
        b.btnWordPrev.visibility = if (idx > 0) View.VISIBLE else View.GONE

        b.tvWordProgress.text = getString(
            if (isHanziMode) R.string.hanzi_checkin_progress else R.string.word_checkin_progress,
            idx + 1, queue.size
        )
        val cid = LearningMath.cardId(card)
        // 如果之前答过这一题又回退重看，展示作答前的原始阶段更直观
        val mem = preAnswerSnapshots[cid] ?: Extras.wordMemory(cid)
        b.tvWordStage.text = when {
            mem.isGraduated -> getString(R.string.word_stage_graduated)
            mem.mastery == CardMastery.LEARNING -> getString(R.string.word_stage_learning)
            mem.stage > 0 -> getString(R.string.word_stage_review, mem.stage)
            else -> getString(R.string.word_stage_new)
        }
        b.tvWordTitle.text = card.title
        b.tvWordSub.text = card.sub
        b.tvWordSub.visibility = if (card.sub.isBlank()) View.GONE else View.VISIBLE

        if (isHanziMode) {
            // 汉字模式：组词用「 · 」排成一行，释义直接展示在 note 区
            b.tvWordNote.text = card.note
            b.tvWordNote.visibility = if (card.note.isBlank()) View.GONE else View.VISIBLE
            val lineText = card.lines.joinToString(" · ")
            b.tvWordLines.text = lineText
            b.tvWordLines.visibility = if (lineText.isBlank()) View.GONE else View.VISIBLE
            b.tvWordLineZh.visibility = View.GONE
        } else {
            val (wordZh, sentZh) = WordCheckInMath.splitZhNote(card.note)
            b.tvWordNote.text = wordZh
            b.tvWordNote.visibility = if (wordZh.isBlank()) View.GONE else View.VISIBLE
            b.tvWordLines.text = card.lines.joinToString("\n")
            b.tvWordLines.visibility = if (card.lines.isEmpty()) View.GONE else View.VISIBLE
            b.tvWordLineZh.text = sentZh
            b.tvWordLineZh.visibility = if (sentZh.isBlank()) View.GONE else View.VISIBLE
        }

        // 默认焦点落在「🔊 读音」，防止孩子连按 OK 键误跳过
        b.btnWordAudio.post { b.btnWordAudio.requestFocus() }
        playWordAudio(card)
    }

    private fun goPrev() {
        if (idx <= 0) return
        idx--
        showCard()
    }

    private fun confirmGraduate() {
        val card = currentCard() ?: return
        val dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(
                getString(
                    if (isHanziMode) R.string.hanzi_graduate_confirm_title else R.string.word_graduate_confirm_title,
                    card.title
                )
            )
            .setMessage(
                getString(if (isHanziMode) R.string.hanzi_graduate_confirm_msg else R.string.word_graduate_confirm_msg)
            )
            .setPositiveButton(getString(R.string.word_graduate_confirm_ok)) { _, _ ->
                answerCurrent(WordCheckInMath.Action.GRADUATE)
            }
            .setNegativeButton(getString(R.string.word_graduate_confirm_cancel), null)
            .create()
        dlg.show()
        // 默认焦点放到「再留着」，防止孩子连按误封存
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
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

    private fun splitShareAndPath(rawPath: String): Pair<String, String> {
        val cleaned = rawPath.removePrefix("smb:").trim()
        val colon = cleaned.indexOf(':')
        val defaultShare = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE
        return if (colon > 0) {
            cleaned.substring(0, colon).trim().ifBlank { defaultShare } to
                cleaned.substring(colon + 1).trimStart('/')
        } else {
            defaultShare to cleaned.trimStart('/')
        }
    }

    private fun loadBitmap(path: String): android.graphics.Bitmap? {
        try {
            if (path.startsWith("asset:")) {
                val rel = path.removePrefix("asset:")
                assets.open(rel).use { return BitmapFactory.decodeStream(it) }
            }
            // 优先命中与锁屏共用的本机图片磁盘缓存
            val diskDir = File(cacheDir, "card_images")
            val hash = RemoteCommand.sha256Hex(path.toByteArray(Charsets.UTF_8)).take(24)
            val diskFile = File(diskDir, "$hash.img")
            if (diskFile.exists() && diskFile.length() > 0L) {
                val cachedBytes = diskFile.readBytes()
                val bmp = BitmapFactory.decodeByteArray(cachedBytes, 0, cachedBytes.size)
                if (bmp != null) return bmp
            }
            val cfg = Store.nas() ?: return null
            val (share, relPath) = splitShareAndPath(path)
            val bytes = Smb.readAllIsolated(cfg, share, relPath, 12_000)
            if (bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) {
                try {
                    diskDir.mkdirs()
                    diskFile.writeBytes(bytes)
                } catch (_: Exception) {}
            }
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
        val expectedId = LearningMath.cardId(card)
        ioPool.execute {
            val localSource = fetchAudioToCache(audioPath)
            val dataSource = localSource ?: buildGatewayAudioUrl(audioPath)
            if (dataSource == null) {
                runOnUiThread { speakTts(speakText) }
                return@execute
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (currentCard()?.let { LearningMath.cardId(it) } != expectedId) return@runOnUiThread
                try {
                    val mp = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(dataSource)
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

    private fun fetchAudioToCache(audioPath: String): String? {
        if (audioPath.startsWith("http://") || audioPath.startsWith("https://")) return audioPath
        val cfg = Store.nas() ?: return null
        val cacheDirAudio = File(cacheDir, "card_audio")
        val ext = audioPath.substringAfterLast('.', "mp3").lowercase().takeIf { it.length in 2..5 } ?: "mp3"
        val hash = RemoteCommand.sha256Hex(audioPath.toByteArray(Charsets.UTF_8)).take(24)
        val cachedF = File(cacheDirAudio, "$hash.$ext")
        if (cachedF.exists() && cachedF.length() > 0L) return cachedF.absolutePath
        return try {
            val (share, relPath) = splitShareAndPath(audioPath)
            val bytes = Smb.readAllIsolated(cfg, share, relPath, 15_000)
            if (bytes.isNotEmpty()) {
                cacheDirAudio.mkdirs()
                cachedF.writeBytes(bytes)
                cachedF.absolutePath
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildGatewayAudioUrl(rawPath: String): String? {
        val cfg = Store.nas() ?: return null
        if (!MediaGateway.isHttpAvailable(cfg)) return null
        val (share, relPath) = splitShareAndPath(rawPath)
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

        // 首次在本轮作答时记录原始快照；若回退改判，先恢复快照再按新选项重算
        val prevSnapshot = preAnswerSnapshots.getOrPut(cid) { Extras.wordMemory(cid) }
        Extras.restoreWordMemory(cid, prevSnapshot)
        Extras.recordWordMemory(cid, action, dayKey)
        sessionChoices[cid] = action
        if (isHanziMode) {
            Extras.markHanziAnswered(cid, dayKey)
            StateBackup.exportAsync("hanzi_checkin") {}
        } else {
            Extras.markWordAnswered(cid, dayKey)
            StateBackup.exportAsync("word_checkin") {}
        }

        if (idx + 1 < queue.size) {
            idx++
            showCard()
        } else {
            showSummary()
        }
    }

    private fun showSummary() {
        releasePlayer()
        val now = ClockGuard.now()
        if (isHanziMode) {
            Extras.markHanziCheckIn(now)
        } else {
            Extras.markWordCheckIn(now)
        }
        val knowCnt = sessionChoices.values.count {
            it == WordCheckInMath.Action.KNOW || it == WordCheckInMath.Action.GRADUATE
        }
        val unknownCnt = sessionChoices.values.count { it == WordCheckInMath.Action.UNKNOWN }
        b.tvSummaryStats.text = getString(
            if (isHanziMode) R.string.hanzi_summary_stats else R.string.word_summary_stats,
            queue.size, knowCnt, unknownCnt
        )
        b.tvSummaryStreak.text = getString(
            if (isHanziMode) R.string.hanzi_summary_streak else R.string.word_summary_streak,
            if (isHanziMode) Extras.hanziStreak(now) else Extras.wordStreak(now)
        )
        b.studyContainer.visibility = View.GONE
        b.summaryCard.visibility = View.VISIBLE
        b.btnSummaryDone.post { b.btnSummaryDone.requestFocus() }
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
