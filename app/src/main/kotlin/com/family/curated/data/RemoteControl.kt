package com.family.curated.data

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.family.curated.LockActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程控制卡 `control.json`（OPT-06）。
 *
 * 家长在电脑 / 手机上改 NAS 上的 `data:curated/control.json`，电视 15 秒内响应：
 *   lockNow   → 立即锁屏（结束临时放行 + 置远程锁定 + 拉起 LockActivity）
 *   addMinutes→ 加时 n 分钟（即使不在允许时段内），同时解除远程锁定
 *   pauseNow  → 暂停当前播放
 *   lockNow 显式改回 false + 新的 updatedAt → 解除远程锁定
 *
 * 与 PlaylistSync 共用 1 秒节拍和同一个后台单线程池，不新增线程、定时器或长连接；
 * 读文件一律走 `Smb.readAllIsolated()`（独立短连接 + 硬超时），
 * 所以播放期间也照常轮询，绝不去抢供货中的那条主 SMB 连接。
 *
 * 幂等：按 `updatedAt` 去重，先记本地再执行；执行后写 `control.ack.json` 回执，
 * 网页端能显示「已生效」。控制文件与 tv.json 分开，家长手改指令碰不坏片单。
 */
object RemoteControl {

    private const val TAG = "RemoteControl"
    /** 与清单轮询同一个周期 */
    const val INTERVAL_MS = 15_000L
    /** 控制文件很小，8 秒读不到就放弃这一轮 */
    private const val READ_TIMEOUT_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CHINA)

    @Volatile private var lastCheckAt = 0L
    @Volatile private var checking = false
    @Volatile private var missingLogged = false
    @Volatile private var lastBadKey = ""

    /**
     * 播放页消费的暂停信号。用单调递增的版本号，而不是一次性布尔：
     * 指令到达时播放器还没启动，「下一次进播放页」也能接住这次暂停。
     */
    @Volatile var pauseVersion: Long = 0L
        private set

    data class Command(
        val key: String,
        val lockNow: Boolean,
        val hasLockField: Boolean,
        val addMinutes: Int,
        val pauseNow: Boolean
    ) {
        fun actions(): List<String> =
            RemoteCommand.actions(lockNow, hasLockField, addMinutes, pauseNow)
    }

    /** 由 KidApp 的 1 秒 tick 调用（在 PlaylistSync.tick 之前，播放中也生效） */
    fun tick(ctx: Context) {
        if (!Extras.feature("remoteControl")) return
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < INTERVAL_MS) return
        lastCheckAt = now
        synchronized(this) {
            if (checking) return
            checking = true
        }
        val app = ctx.applicationContext
        PlaylistSync.submit {
            try {
                check(app)
            } catch (e: Exception) {
                // 控制卡的任何异常都不能影响清单同步 / 播放
                Log.w(TAG, "控制卡检查异常（已忽略）: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                synchronized(this) { checking = false }
            }
        }
    }

    /** 立即查一次（设置页打开远程控制开关时用，不用等 15 秒） */
    fun nudge() {
        lastCheckAt = 0L
    }

    private fun check(ctx: Context) {
        val cfg = Store.nas() ?: return
        val share = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE

        val bytes = try {
            Smb.readAllIsolated(cfg, share, controlPath(), READ_TIMEOUT_MS)
        } catch (e: Exception) {
            // 绝大多数时候是「家长还没建这个文件」，每 15 秒打一条日志会刷屏
            if (!missingLogged) {
                missingLogged = true
                Log.i(TAG, "没有读到 control.json，本轮忽略（${e.javaClass.simpleName}）")
            }
            return
        }
        missingLogged = false

        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            // EX-14：写坏的 JSON 只忽略这一条指令，绝不改片单、绝不崩。
            // 同一份坏内容只打一次日志，否则家长没发现文件时每 15 秒刷一条。
            val badKey = text.hashCode().toString()
            if (badKey != lastBadKey) {
                lastBadKey = badKey
                Log.w(TAG, "control.json 不是合法 JSON，忽略本次（不影响片单同步）")
            }
            return
        }
        lastBadKey = ""
        if (o.length() == 0) return

        val cmd = Command(
            key = RemoteCommand.idempotencyKey(o.optString("updatedAt"), text),
            lockNow = o.optBoolean("lockNow", false),
            hasLockField = o.has("lockNow"),
            addMinutes = RemoteCommand.clampMinutes(o.optInt("addMinutes", 0)),
            pauseNow = o.optBoolean("pauseNow", false)
        )
        if (cmd.actions().isEmpty()) return
        if (cmd.key == Store.lastControlAt) return

        // 先落幂等键再执行：哪怕下面抛异常、或回执写失败，也不会重复执行同一张卡
        Store.lastControlAt = cmd.key
        val actions = cmd.actions()
        main.post { applyOnMain(ctx, cmd) }
        Log.i(TAG, "收到远程指令 ${cmd.key}：$actions")

        // 回执写在后台线程；NAS 只读时指令照样生效，只是网页看不到「已生效」
        try {
            writeAck(cfg, share, cmd.key, actions)
        } catch (e: Exception) {
            Log.w(TAG, "写 control.ack.json 失败（指令已执行）: ${e.message}")
        }
    }

    private fun applyOnMain(ctx: Context, c: Command) {
        try {
            if (c.lockNow) {
                Extras.grantTemp(0)          // 结束任何临时放行
                Extras.setRemoteLock(true)   // 门禁读得到的状态，见 GateKeeper
                launchLock(ctx)
                Toast.makeText(ctx, "家长已远程锁屏", Toast.LENGTH_LONG).show()
            } else if (c.hasLockField && Extras.remoteLockActive()) {
                Extras.setRemoteLock(false)
                Toast.makeText(ctx, "家长已解除远程锁定", Toast.LENGTH_LONG).show()
            }
            if (c.addMinutes > 0) {
                Extras.setRemoteLock(false)
                Extras.grantTemp(c.addMinutes)
                Toast.makeText(ctx, "家长加了 ${c.addMinutes} 分钟", Toast.LENGTH_LONG).show()
            }
            if (c.pauseNow) {
                pauseVersion++
            }
        } catch (e: Exception) {
            Log.w(TAG, "执行远程指令失败: ${e.message}")
        }
    }

    private fun launchLock(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(ctx, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            // 后台启动被 ROM 拦下也没关系：KidApp 下一秒会按门禁状态盖锁屏
            Log.i(TAG, "后台拉起锁屏被拦，交给 KidApp.tick 兜底")
        }
    }

    private fun writeAck(cfg: NasConfig, share: String, key: String, actions: List<String>) {
        val ack = JSONObject().apply {
            put("updatedAt", key)
            put("appliedAt", synchronized(stamp) { stamp.format(Date()) })
            put("ok", true)
            put("actions", JSONArray(actions))
            put("message", "电视已收到指令，最长 15 秒内生效")
        }
        val bytes = (ack.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        Smb.writeIsolated(cfg, share, ackPath(), bytes, 12_000)
    }

    /** control.json 与 tv.json 放在同一个目录、同一个共享里 */
    private fun dirOfPlaylist(): String =
        Store.playlistPath.substringBeforeLast('/', "")

    fun controlPath(): String =
        dirOfPlaylist().let { if (it.isEmpty()) "control.json" else "$it/control.json" }

    fun ackPath(): String =
        dirOfPlaylist().let { if (it.isEmpty()) "control.ack.json" else "$it/control.ack.json" }
}
