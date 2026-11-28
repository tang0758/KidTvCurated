package com.family.curated

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.family.curated.data.AutoNext
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.data.QueueItem
import com.family.curated.data.KioskKeys
import com.family.curated.data.MediaGateway
import com.family.curated.data.PlaylistSync
import com.family.curated.data.RemoteControl
import com.family.curated.data.Smb
import com.family.curated.data.SmbDataSource
import com.family.curated.data.SmbPlaybackContext
import com.family.curated.data.SoftwareFirstSelector
import com.family.curated.data.Store
import com.family.curated.data.TimeNotice
import com.family.curated.data.WatchClock
import com.family.curated.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KEY = "key"
        const val EXTRA_GATE_SECTION = "gate"
        const val EXTRA_SEEK_LOCK = "seekLock"
        const val EXTRA_SKIP_INTRO = "skipIntroSec"
        const val EXTRA_SKIP_OUTRO = "skipOutroSec"

        private const val TAG = "PlayerActivity"
        private const val MAX_AUTO_RETRIES = 3
        /** 已经稳定播放这么久之后再次出错，视为一次新故障，重新给满重试额度 */
        private const val STABLE_PLAY_MS = 30_000L
    }

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var key: String = ""
    private var file: String = ""
    private var doneMarked = false
    private var seekLock = false
    private var skipIntroSec = 0
    private var skipOutroSec = 0
    private var useSoftwareCodec = false
    private var currentSpeedIndex = 1
    private val speeds = floatArrayOf(0.75f, 1.0f, 1.25f)
    private val speedLabels = arrayOf("0.75x", "1.0x", "1.25x")
    /** 必看内容只允许 0.75x 和 1.0x，禁用 1.25x */
    private val allowedSpeedCount: Int get() = if (seekLock) 2 else speeds.size
    private var isZoomMode = false
    /** 底部选项栏焦点：-1=未聚焦（正常播放控制），0=倍速，1=画面比例 */
    private var optionFocusIndex = -1

    private var lastPos = 0L

    private var lastGuardAt = 0L
    private var lockLaunched = false
    private var lastBlockToastAt = 0L

    private var retryCount = 0
    private var retrying = false
    private var resumeOverride: Long? = null
    private var lastReadyAt = 0L
    private var tickerStarted = false
    /** 已经消费过的远程暂停指令版本，避免重放历史指令 */
    private var seenPauseVersion = 0L

    private val ui = Handler(Looper.getMainLooper())
    private var hideUi = Runnable {
        optionFocusIndex = -1
        renderOptionFocus()
        b.controls.visibility = View.GONE
    }

    // ---------- OPT-11 自动连播 ----------
    private var queueRaw = ""
    private var queue: List<QueueItem> = emptyList()
    private var queueIndex = -1
    private var endedHandled = false
    private var autoNextRunning = false
    private var autoNextCancelled = false
    private var nextCountdown = 0
    private var pendingNext: QueueItem? = null

    /** 5 秒里家长仍可喊停：暂停 / 锁屏 / 限额到点都在这一秒级检查里拦下来 */
    private val nextTick = object : Runnable {
        override fun run() {
            if (!autoNextRunning || isFinishing || isDestroyed) return
            if (RemoteControl.pauseVersion != seenPauseVersion) {
                // 家长只点了「暂停」，不是锁屏：取消连播、回到列表就够了，别误弹锁屏
                Log.i(TAG, "连播倒计时被家长暂停打断")
                cancelAutoNext()
                return
            }
            if (!GateKeeper.status().open) {
                Log.i(TAG, "连播倒计时期间门禁关闭，交给锁屏")
                stopForLock()
                return
            }
            nextCountdown--
            if (nextCountdown <= 0) {
                playNext()
                return
            }
            b.tvNextCount.text = "${nextCountdown}s"
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Extras.init(this)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        seenPauseVersion = RemoteControl.pauseVersion

        file = intent.getStringExtra(EXTRA_FILE) ?: ""
        key = intent.getStringExtra(EXTRA_KEY) ?: file
        seekLock = intent.getBooleanExtra(EXTRA_SEEK_LOCK, false) ||
            intent.getStringExtra(EXTRA_GATE_SECTION) != null
        skipIntroSec = intent.getIntExtra(EXTRA_SKIP_INTRO, 0)
        skipOutroSec = intent.getIntExtra(EXTRA_SKIP_OUTRO, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        b.tvSpeed.setOnClickListener { toggleSpeed(); showControls() }
        b.tvResize.setOnClickListener { toggleResizeMode(); showControls() }

        b.tvTitle.text = title
        b.tvTitle.postDelayed({ b.tvTitle.animate().alpha(0f).setDuration(600).start() }, 4000)
        b.tvState.text = if (seekLock) "必看内容：不能快进哦" else ""

        if (file.isEmpty()) {
            fail("没有指定要播放的文件")
            return
        }

        queueRaw = intent.getStringExtra(AutoNext.EXTRA_QUEUE) ?: ""
        queue = AutoNext.decode(queueRaw)
        queueIndex = intent.getIntExtra(AutoNext.EXTRA_QUEUE_INDEX, -1)
        // OPT-12：只在真的点开一次时记「最近看过」，不拿进度落盘时间当排序依据
        Store.notePlayed(key)
        // OPT-08：周报要按「这周点开过几天」算反复看，点开就记一次（同一天同一部只记一次）
        Extras.notePlayedVideo(key)

        val cfg = Store.nas()
        if (cfg == null) {
            fail("还没有配置 NAS")
            return
        }
        SmbPlaybackContext.config = cfg

        startPlayer()
    }

    private fun startPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 90_000, 2_500, 4_000)
            .build()

        val renderersFactory = DefaultRenderersFactory(this).apply {
            if (useSoftwareCodec) {
                setMediaCodecSelector(SoftwareFirstSelector())
            }
        }

        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(MediaGateway.createDataSourceFactory()))
            .setLoadControl(loadControl)
            .build()

        player = p
        b.playerView.player = p
        applySpeed()
        applyResizeMode()

        val cfg = Store.nas() ?: return
        val uri = MediaGateway.resolveMediaUri(cfg, file)
        p.setMediaItem(MediaItem.fromUri(uri))

        // 自动重试时接着刚才的位置播，不用等进度落盘
        val resumeAt = resumeOverride ?: Store.progress(key)
        resumeOverride = null
        val lastDur = Store.duration(key)
        val shouldResume = resumeAt > 5_000 && (lastDur == 0L || resumeAt < lastDur - 5_000)

        if (shouldResume) {
            lastPos = resumeAt
            p.seekTo(resumeAt)
        } else if (skipIntroSec > 0) {
            val target = (skipIntroSec * 1000L).coerceAtLeast(0)
            lastPos = target
            p.seekTo(target)
        } else {
            lastPos = 0L
        }

        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        lastReadyAt = SystemClock.elapsedRealtime()
                        retrying = false
                        b.pbLoading.visibility = View.GONE
                        b.tvError.visibility = View.GONE
                        showControls()
                    }
                    Player.STATE_BUFFERING -> b.pbLoading.visibility = View.VISIBLE
                    Player.STATE_ENDED -> {
                        if (endedHandled) return
                        endedHandled = true
                        markDone()
                        savePosition()
                        // 自动重试会重建播放器，STATE_ENDED 只认第一次；
                        // 没有队列 / 开关没开时仍是原来的「播完回到剧集列表」
                        maybeAutoNext()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                b.pbLoading.visibility = if (!isPlaying && p.playbackState == Player.STATE_BUFFERING)
                    View.VISIBLE else View.GONE
                // 只统计「真的在放」的时间：暂停、缓冲都不算
                if (isPlaying) WatchClock.start(clockToken) else WatchClock.stop(clockToken)
            }

            /** 必看内容兜底拦截：不管是谁发起的向前 seek，一律拉回当前位置 */
            override fun onPositionDiscontinuity(reason: Int) {
                if (!seekLock || reason != Player.DISCONTINUITY_REASON_SEEK) return
                val cur = p.currentPosition
                if (cur > lastPos + 2_000L) {
                    p.seekTo(lastPos)
                    nudge("必看内容不能快进哦")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })

        p.prepare()
        if (!tickerStarted) {
            tickerStarted = true
            startTicker()
        }
    }

    /** 只有「文件确实不存在 / 没权限」这类铁定重试也没用的错误才直接放弃 */
    private fun isRetryable(e: PlaybackException): Boolean = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> false
        else -> true
    }

    private fun handleError(error: PlaybackException) {
        Log.w(TAG, "播放出错 code=${error.errorCodeName} message=${error.message}", error)
        if (!useSoftwareCodec && (error.errorCode in 4000..4999)) {
            Log.i(TAG, "检测到解码错误 code=${error.errorCodeName}，切换到软解回退 SoftwareFirstSelector")
            useSoftwareCodec = true
            scheduleRetry()
            return
        }
        val cfg = Store.nas()
        if (cfg != null && MediaGateway.isHttpAvailable(cfg)) {
            MediaGateway.markHttpUnavailable()
        }
        // 已经正常播了一阵子才出错，算新故障，把重试额度还回来
        if (lastReadyAt > 0 && SystemClock.elapsedRealtime() - lastReadyAt > STABLE_PLAY_MS) {
            retryCount = 0
        }
        if (retryCount < MAX_AUTO_RETRIES && isRetryable(error)) {
            scheduleRetry()
        } else {
            fail(describe(error))
        }
    }

    /**
     * 重建播放器重试。出错时 ExoPlayer 已经处在某些内部状态里，最干净的做法是
     * 释放后重新 prepare，同时强制丢弃 SMB 连接（第一次失败多半就是它半死了）。
     */
    private fun scheduleRetry() {
        retryCount++
        retrying = true
        resumeOverride = lastPos.coerceAtLeast(0)
        val n = retryCount
        b.pbLoading.visibility = View.VISIBLE
        b.tvError.visibility = View.VISIBLE
        b.tvError.text = "刚才没读成功，正在自动重连（$n/$MAX_AUTO_RETRIES）…\n\n" +
            "通常是 NAS 连接抖动，不一定是视频格式问题"

        ui.postDelayed({
            // 断链放到后台线程，避免在主线程等网络超时
            Thread {
                Smb.reset()
                ui.post {
                    if (isFinishing || isDestroyed) return@post
                    b.playerView.player = null
                    val old = player
                    player = null
                    try { old?.release() } catch (e: Exception) { }
                    startPlayer()
                }
            }.start()
        }, 700L * n)
    }

    private fun describe(e: PlaybackException): String {
        val cause = e.cause?.message ?: e.message ?: ""
        val hint = when (e.errorCode) {
            in 2000..2999 -> "NAS 读取出问题：网络抖动、连接被断开或共享暂时不可用。"
            in 3000..3999 -> "文件头没读完整。首次读取被中断也会报这个，不一定是格式问题。"
            in 4000..4999 -> "电视解码器可能真的不支持这个编码（例如 HEVC 10bit、DTS 音轨）。"
            else -> "可能是 NAS 连接问题，也可能是这个编码电视不支持。"
        }
        return "自动重试 $MAX_AUTO_RETRIES 次后还是没能播放。\n\n" +
            "$hint\n\n" +
            "错误码：${e.errorCodeName}\n$cause\n\n" +
            "按遥控器「确认」键再试一次；若一直失败，检查 NAS 是否在线、文件是否还在。"
    }

    private fun fail(msg: String) {
        retrying = false
        b.pbLoading.visibility = View.GONE
        b.tvError.visibility = View.VISIBLE
        b.tvError.text = msg
    }

    private fun nudge(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockToastAt < 2500) return
        lastBlockToastAt = now
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        showControls()
    }

    private fun startTicker() {
        ui.post(object : Runnable {
            override fun run() {
                val p = player
                if (p == null) {
                    // 自动重试期间播放器会短暂为空，别让计时器就此停掉
                    ui.postDelayed(this, 700)
                    return
                }
                val nowRt = SystemClock.elapsedRealtime()

                // 家长在手机上点了「暂停」：版本号变化就暂停当前播放
                val pv = RemoteControl.pauseVersion
                if (pv != seenPauseVersion) {
                    seenPauseVersion = pv
                    try { p.pause() } catch (e: Exception) { }
                    Log.i(TAG, "远程暂停指令已执行（version=$pv）")
                    Toast.makeText(this@PlayerActivity, "家长暂停了播放", Toast.LENGTH_LONG).show()
                    showControls()
                }

                // 剩余时间预告（OPT-05）：右上角常显 + 5 分钟 / 1 分钟各一次 Toast
                updateRemaining()

                // 计时不在播放页做：统一交给 WatchClock（它只认「主人令牌 + 单调时间」），
                // 播放页只负责在 onStart / onPause 里 start / stop
                val pos = p.currentPosition.coerceAtLeast(0)
                val dur = p.duration
                if (dur > 0) {
                    b.pbProgress.progress = ((pos.toFloat() / dur) * 1000).toInt()
                    b.tvDuration.text = fmt(dur)
                    checkNearingEnd(pos, dur)
                }
                b.tvPosition.text = fmt(pos)
                lastPos = pos

                // 到点 / 时长用尽：停下并交给锁屏
                if (nowRt - lastGuardAt >= 2_000L) {
                    lastGuardAt = nowRt
                    if (!GateKeeper.status().open) stopForLock()
                }

                ui.postDelayed(this, 700)
            }
        })
    }

    /**
     * OPT-11：一集播完，先过三道闸门再决定要不要连下一集。
     * 门禁不开放（时段到点 / 限额用尽 / 家长远程锁屏）时不停在倒计时上，
     * 直接交给锁屏——这正是「第 5 集时限额到点则停止并锁屏」的验收点。
     */
    private fun maybeAutoNext() {
        val next = AutoNext.next(queue, queueIndex)
        if (!GateKeeper.status().open) {
            Log.i(TAG, "不连播：门禁已关闭")
            stopForLock()
            return
        }
        val featureOn = Extras.feature("autoNext")
        if (!AutoNext.allowed(featureOn, true, next != null, autoNextCancelled)) {
            Log.i(TAG, "不连播：autoNext=$featureOn 有下一集=${next != null} 已取消=$autoNextCancelled")
            finish()
            return
        }
        val n = next ?: return
        pendingNext = n
        autoNextRunning = true
        nextCountdown = AutoNext.COUNTDOWN_SECONDS
        b.nextOverlay.visibility = View.VISIBLE
        b.tvNextTitle.text = "下一集：${n.title}"
        b.tvNextCount.text = "${nextCountdown}s"
        b.btnCancelNext.setOnClickListener { cancelAutoNext() }
        ui.postDelayed(nextTick, 1000)
        Log.i(TAG, "自动连播倒计时：${n.title}（${nextCountdown}s）")
    }

    /** 复用现有 ExoPlayer 实例无缝切集，消除黑屏闪烁与 Activity 重建 (OPT-22) */
    private fun playNext() {
        val n = pendingNext ?: return
        autoNextRunning = false
        ui.removeCallbacks(nextTick)
        b.nextOverlay.visibility = View.GONE
        Log.i(TAG, "自动连播 (实例复用) → ${n.title}")

        file = n.file
        key = n.key
        val title = n.title
        queueIndex += 1
        doneMarked = false
        endedHandled = false
        lastPos = 0L
        retryCount = 0
        useSoftwareCodec = false

        Store.notePlayed(key)
        Extras.notePlayedVideo(key)

        b.tvTitle.text = title
        b.tvTitle.alpha = 1f
        b.tvTitle.postDelayed({ b.tvTitle.animate().alpha(0f).setDuration(600).start() }, 4000)

        val cfg = Store.nas() ?: return
        val p = player
        if (p != null) {
            val uri = MediaGateway.resolveMediaUri(cfg, file)
            p.setMediaItem(MediaItem.fromUri(uri))
            val resumeAt = Store.progress(key)
            val lastDur = Store.duration(key)
            val shouldResume = resumeAt > 5_000 && (lastDur == 0L || resumeAt < lastDur - 5_000)
            if (shouldResume) {
                lastPos = resumeAt
                p.seekTo(resumeAt)
            } else if (skipIntroSec > 0) {
                val target = (skipIntroSec * 1000L).coerceAtLeast(0)
                lastPos = target
                p.seekTo(target)
            } else {
                lastPos = 0L
                p.seekTo(0)
            }
            p.prepare()
            p.play()
        } else {
            startPlayer()
        }
    }

    private fun cancelAutoNext() {
        autoNextRunning = false
        autoNextCancelled = true
        ui.removeCallbacks(nextTick)
        b.nextOverlay.visibility = View.GONE
        Log.i(TAG, "自动连播已取消（家长/孩子按了返回）")
        finish()
    }

    private fun stopForLock() {
        if (lockLaunched) return
        lockLaunched = true
        autoNextRunning = false
        ui.removeCallbacks(nextTick)
        b.nextOverlay.visibility = View.GONE
        flushWatch()
        try { player?.pause() } catch (e: Exception) { }
        startActivity(
            android.content.Intent(this, LockActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    /**
     * 右上角「今天还剩 N 分钟」。取「最早会锁住孩子」的那条约束
     * （时段结束 / 每日限额 / 临时放行），不是只看限额，免得说错话。
     * 到 5 分钟、1 分钟各弹一次，同一天去重，暂停或重进页面都不重复刷屏。
     */
    private fun updateRemaining() {
        if (!TimeNotice.enabled()) {
            if (b.tvRemain.visibility != View.GONE) b.tvRemain.visibility = View.GONE
            return
        }
        val left = TimeNotice.remaining()
        val text = TimeNotice.label(left)
        // 文案变了才落一条日志：家长说「没看到提示」时能查到电视上到底显示了什么
        if (b.tvRemain.text.toString() != text) Log.i(TAG, "剩余时间预告：$text")
        if (text.isBlank()) {
            b.tvRemain.visibility = View.GONE
        } else {
            b.tvRemain.text = text
            b.tvRemain.visibility = View.VISIBLE
        }
        val notice = TimeNotice.tickPlayer(left)
        if (notice != null) {
            Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
            Log.i(TAG, "阈值提示：$notice")
        }
    }

    private fun checkNearingEnd(pos: Long, dur: Long) {
        if (doneMarked || dur <= 0) return
        val ratio = pos.toFloat() / dur
        if (ratio >= Extras.passRatio()) markDone()
        if (skipOutroSec > 0 && pos >= dur - (skipOutroSec * 1000L)) {
            if (!endedHandled) {
                endedHandled = true
                markDone()
                savePosition()
                maybeAutoNext()
            }
        }
    }

    private fun markDone() {
        if (doneMarked || key.isEmpty()) return
        doneMarked = true
        Store.markDone(key)
        Store.setProgress(key, 0)
        Extras.addVideo()
        if (seekLock) {
            val title = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty().ifBlank { key }
            com.family.curated.data.DailyLogStore.recordMustWatchVideo(
                com.family.curated.data.ClockGuard.day(), key, title
            )
        }
    }

    private fun savePosition() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur > 0) Store.setDuration(key, dur)
        if (!doneMarked) {
            Store.setProgress(key, if (pos > 0) pos else 0)
        }
    }

    private fun flushWatch() {
        WatchClock.stop(clockToken)
        com.family.curated.data.DailyLogStore.refreshTodayWatchMs()
    }

    private fun showControls(timeoutMs: Long = 4000L) {
        b.controls.visibility = View.VISIBLE
        b.controls.alpha = 1f
        renderOptionFocus()
        ui.removeCallbacks(hideUi)
        ui.postDelayed(hideUi, timeoutMs)
    }

    private fun hideControls() {
        optionFocusIndex = -1
        renderOptionFocus()
        ui.removeCallbacks(hideUi)
        b.controls.visibility = View.GONE
    }

    private fun renderOptionFocus() {
        val speedFocused = optionFocusIndex == 0
        val resizeFocused = optionFocusIndex == 1
        b.tvSpeed.isSelected = speedFocused
        b.tvResize.isSelected = resizeFocused
        b.tvSpeed.setTextColor(if (speedFocused) 0xFFFFFFFF.toInt() else 0xFFE0E0E0.toInt())
        b.tvResize.setTextColor(if (resizeFocused) 0xFFFFFFFF.toInt() else 0xFFE0E0E0.toInt())
        b.tvSpeed.scaleX = if (speedFocused) 1.06f else 1f
        b.tvSpeed.scaleY = if (speedFocused) 1.06f else 1f
        b.tvResize.scaleX = if (resizeFocused) 1.06f else 1f
        b.tvResize.scaleY = if (resizeFocused) 1.06f else 1f
        b.tvOptionHint.setText(
            if (optionFocusIndex >= 0) R.string.player_hint_options else R.string.player_hint_idle
        )
    }

    private fun toggleSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % allowedSpeedCount
        applySpeed()
    }

    private fun applySpeed() {
        if (currentSpeedIndex >= allowedSpeedCount) currentSpeedIndex = 1
        val spd = speeds[currentSpeedIndex]
        player?.playbackParameters = PlaybackParameters(spd)
        b.tvSpeed.text = getString(R.string.player_speed_label, speedLabels[currentSpeedIndex])
    }

    private fun toggleResizeMode() {
        isZoomMode = !isZoomMode
        applyResizeMode()
    }

    private fun applyResizeMode() {
        b.playerView.resizeMode = if (isZoomMode)
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        b.tvResize.setText(if (isZoomMode) R.string.player_resize_zoom else R.string.player_resize_fit)
    }

    /** 必看内容直接禁止快进，允许回退重看 */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (seekLock && deltaMs > 0) {
            nudge("必看内容不能快进哦")
            showControls()
            return
        }
        val dur = p.duration
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        if (dur > 0) target = target.coerceAtMost(dur - 1_000)
        p.seekTo(target)
        showControls()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 连播倒计时期间遥控器完全交给这层浮层：确认 = 马上播，其余（含返回）= 取消
        if (autoNextRunning) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    playNext()
                    return true
                }
                else -> {
                    cancelAutoNext()
                    return true
                }
            }
        }
        if (KioskKeys.systemBlocked(keyCode)) return true
        // 失败页上按「确认」可以手动重来一次，并重新给满自动重试额度
        if (b.tvError.visibility == View.VISIBLE && !retrying) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    retryCount = 0
                    scheduleRetry()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
            }
        }
        val p = player ?: return super.onKeyDown(keyCode, event)
        // 选项栏已聚焦：左右切换按钮、确认修改、上/返回退出选项栏（不误退播放页）
        if (optionFocusIndex >= 0 && b.controls.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (optionFocusIndex > 0) optionFocusIndex--
                    showControls(6000L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (optionFocusIndex < 1) optionFocusIndex++
                    showControls(6000L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (optionFocusIndex == 0) toggleSpeed() else toggleResizeMode()
                    showControls(6000L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    optionFocusIndex = -1
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    hideControls()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (p.isPlaying) p.pause() else p.play()
                    showControls(6000L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showControls(6000L)
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (b.controls.visibility != View.VISIBLE) {
                    showControls()
                } else {
                    if (p.isPlaying) p.pause() else p.play()
                    showControls()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (p.isPlaying) p.pause() else p.play()
                showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                optionFocusIndex = 0
                showControls(6000L)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (b.controls.visibility != View.VISIBLE) showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-15_000)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(15_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                seekBy(60_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                seekBy(-60_000)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    /** WatchClock 的令牌：一个视频一个，重进页面用同一个就不会重复计 */
    private val clockToken: String get() = "player:$key"

    override fun onStart() {
        super.onStart()
        // 播放期间让清单轮询 / 封面探测让路，避免一条 SMB 连接被后台任务抢死
        SmbPlaybackContext.playing = true
    }

    override fun onPause() {
        super.onPause()
        optionFocusIndex = -1
        // 页面被盖住（家长锁屏、切走）时不许后台继续倒计时启动下一集
        if (autoNextRunning) {
            autoNextRunning = false
            autoNextCancelled = true
            ui.removeCallbacks(nextTick)
            b.nextOverlay.visibility = View.GONE
        }
        SmbPlaybackContext.playing = false
        PlaylistSync.nudge()
        flushWatch()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        flushWatch()
        savePosition()
        b.playerView.player = null
        player?.release()
        player = null
    }
}
