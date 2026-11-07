package com.family.curated

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

        val resumeAt = Store.progress(key)
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
                fail(describe(error))
            }
        })

        p.prepare()
        lastTickAt = SystemClock.elapsedRealtime()
        startTicker()
    }

    private fun describe(e: PlaybackException): String {
        val cause = e.cause?.message ?: e.message
        return "这段播不了。\n\n可能原因：NAS 连不上，或者这个格式电视解不了。\n\n$cause"
    }

    private fun fail(msg: String) {
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
                val p = player ?: return
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
