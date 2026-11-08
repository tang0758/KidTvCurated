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
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.family.curated.data.Extras
import com.family.curated.data.GateKeeper
import com.family.curated.data.KioskKeys
import com.family.curated.data.Smb
import com.family.curated.data.SmbDataSource
import com.family.curated.data.SmbPlaybackContext
import com.family.curated.data.Store
import com.family.curated.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KEY = "key"
        const val EXTRA_GATE_SECTION = "gate"
        const val EXTRA_SEEK_LOCK = "seekLock"

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

    /** 这个视频「真正看到过」的最远位置，快进不能超过它 */
    private var frontier = 0L
    private var lastFrontierSaved = 0L
    private var lastPos = 0L

    private var pendingWatchMs = 0L
    private var lastTickAt = 0L
    private var lastGuardAt = 0L
    private var lockLaunched = false
    private var lastBlockToastAt = 0L

    private var retryCount = 0
    private var retrying = false
    private var resumeOverride: Long? = null
    private var lastReadyAt = 0L
    private var tickerStarted = false

    private val ui = Handler(Looper.getMainLooper())
    private var hideUi = Runnable { b.controls.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Extras.init(this)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        file = intent.getStringExtra(EXTRA_FILE) ?: ""
        key = intent.getStringExtra(EXTRA_KEY) ?: file
        seekLock = intent.getBooleanExtra(EXTRA_SEEK_LOCK, false) ||
            intent.getStringExtra(EXTRA_GATE_SECTION) != null
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        b.tvTitle.text = title
        b.tvTitle.postDelayed({ b.tvTitle.animate().alpha(0f).setDuration(600).start() }, 4000)
        b.tvState.text = if (seekLock) "必看内容：没看过的地方不能快进哦" else ""

        if (file.isEmpty()) {
            fail("没有指定要播放的文件")
            return
        }

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

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory()))
            .setLoadControl(loadControl)
            .build()

        player = p
        b.playerView.player = p

        val uri = Uri.parse("smb://nas/" + Uri.encode(file))
        p.setMediaItem(MediaItem.fromUri(uri))

        // 自动重试时接着刚才的位置播，不用等进度落盘
        val resumeAt = resumeOverride ?: Store.progress(key)
        resumeOverride = null
        val lastDur = Store.duration(key)
        val shouldResume = resumeAt > 5_000 && (lastDur == 0L || resumeAt < lastDur - 5_000)

        // 续播位置本身属于「已经看过」，算进可 seek 的边界
        frontier = maxOf(Extras.frontier(key), resumeAt, lastPos)
        lastFrontierSaved = frontier
        if (shouldResume) p.seekTo(minOf(resumeAt, frontier))

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
                        markDone()
                        savePosition()
                        finish()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                b.pbLoading.visibility = if (!isPlaying && p.playbackState == Player.STATE_BUFFERING)
                    View.VISIBLE else View.GONE
            }

            /** 不管是谁发起的 seek（遥控器、PlayerView、进度条），越过已看边界一律拉回来 */
            override fun onPositionDiscontinuity(reason: Int) {
                if (!seekLock || reason != Player.DISCONTINUITY_REASON_SEEK) return
                val cur = p.currentPosition
                if (cur > frontier + 2_000L) {
                    p.seekTo(frontier)
                    nudge("后面的还没看过，要一小段一小段看哦")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })

        p.prepare()
        if (!tickerStarted) {
            tickerStarted = true
            lastTickAt = SystemClock.elapsedRealtime()
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
                val dt = (nowRt - lastTickAt).coerceIn(0L, 2_000L)
                lastTickAt = nowRt

                if (p.isPlaying) {
                    pendingWatchMs += dt
                    if (pendingWatchMs >= 3_000L) {
                        Extras.addWatchMs(pendingWatchMs)
                        pendingWatchMs = 0L
                    }
                }

                val pos = p.currentPosition.coerceAtLeast(0)
                val dur = p.duration
                if (dur > 0) {
                    b.pbProgress.progress = ((pos.toFloat() / dur) * 1000).toInt()
                    b.tvDuration.text = fmt(dur)
                    checkNearingEnd(pos, dur)
                }
                b.tvPosition.text = fmt(pos)

                // 只在「连续观看」时推进边界；跳跃式的位置变化不算看过
                if (seekLock && p.isPlaying && pos >= lastPos && pos - lastPos <= 2_500L) {
                    if (pos > frontier) {
                        frontier = pos
                        if (frontier - lastFrontierSaved >= 5_000L) {
                            Extras.setFrontier(key, frontier)
                            lastFrontierSaved = frontier
                        }
                    }
                }
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

    private fun stopForLock() {
        if (lockLaunched) return
        lockLaunched = true
        flushWatch()
        Extras.setFrontier(key, frontier)
        try { player?.pause() } catch (e: Exception) { }
        startActivity(
            android.content.Intent(this, LockActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun checkNearingEnd(pos: Long, dur: Long) {
        if (doneMarked || dur <= 0) return
        val ratio = pos.toFloat() / dur
        if (ratio >= Extras.passRatio()) markDone()
    }

    private fun markDone() {
        if (doneMarked || key.isEmpty()) return
        doneMarked = true
        Store.markDone(key)
        Store.setProgress(key, 0)
        frontier = maxOf(frontier, player?.duration ?: 0L)
        Extras.setFrontier(key, frontier)
        Extras.addVideo()
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
        if (pendingWatchMs > 0) {
            Extras.addWatchMs(pendingWatchMs)
            pendingWatchMs = 0L
        }
    }

    private fun showControls() {
        b.controls.visibility = View.VISIBLE
        b.controls.alpha = 1f
        ui.removeCallbacks(hideUi)
        ui.postDelayed(hideUi, 4000)
    }

    /** 快进时把目标限制在已看边界内 */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        if (dur > 0) target = target.coerceAtMost(dur - 1_000)
        if (seekLock && target > frontier + 1_000L && deltaMs > 0) {
            target = frontier
            nudge("后面的还没看过，不能快进哦")
        }
        p.seekTo(target)
        showControls()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (p.isPlaying) p.pause() else p.play()
                showControls()
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

    override fun onPause() {
        super.onPause()
        flushWatch()
        Extras.setFrontier(key, frontier)
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        flushWatch()
        Extras.setFrontier(key, frontier)
        savePosition()
        b.playerView.player = null
        player?.release()
        player = null
    }
}
