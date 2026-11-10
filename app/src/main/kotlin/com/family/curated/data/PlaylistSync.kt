package com.family.curated.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 清单自动同步：让 NAS 上的 tv.json 成为唯一事实来源。
 *
 * 两条通路，互为兜底：
 *  1. 轮询 —— KidApp 的 1 秒 tick 调 tick()，内部按 intervalSeconds 节流，
 *     后台线程读一次 tv.json 全文（约 8KB）与本地缓存逐字比较，内容没变就什么都不做。
 *     不依赖电脑、adb 或任何外部服务，电视自己就能感知。
 *  2. 广播 —— 网页编辑器保存成功后发一条显式广播，收到立即检查，秒级生效。
 *
 * 只有内容真的变了才重绘界面；连不上 NAS 时静默退避，绝不弹错误打扰孩子。
 */
object PlaylistSync {

    private const val TAG = "PlaylistSync"
    const val ACTION_REFRESH = "com.family.curated.PLAYLIST_CHANGED"

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newSingleThreadExecutor { r ->
        Thread(r, "playlist-sync").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile private var initialized = false
    @Volatile private var lastCheckAt = 0L
    @Volatile private var failStreak = 0

    fun interface Listener {
        fun onPlaylistChanged(pl: Playlist, reason: String)
    }

    /** 清单里 apps 段的确定性指纹，用来判断家长是不是改了应用列表 */
    fun appsSignature(c: AppsConfig): String = buildString {
        append(c.enabled).append('|').append(c.title).append('|')
        append(c.showAfterDaily).append('|').append(c.maxMinutes).append('|')
        append(c.entries.joinToString(",") { "${it.spec}=${it.label}" })
    }

    /** 冷启动时把清单里的时段 / PIN / 应用配置同步到本地，别的界面才能独立读到 */
    fun applyToLocal(pl: Playlist) {
        Extras.saveSchedule(pl.schedule)
        Extras.savePin(pl.pin)
        Extras.savePassRatio(pl.passRatio)
        Extras.saveLearning(pl.learning ?: Learning())
        Extras.saveKiosk(pl.kiosk)
        Extras.saveApps(pl.apps)
        Extras.saveFeatures(pl.features)
        Store.playlistAppsSignature = appsSignature(pl.apps)
        Store.autoRefreshEnabled = pl.autoRefresh.enabled
        Store.autoRefreshIntervalMs = pl.autoRefresh.intervalSeconds * 1000L
    }

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Store / Extras 由 KidApp.onCreate 先初始化；这里再兜一次，广播拉起进程时也安全
            Store.init(ctx)
            Extras.init(ctx)
            initialized = true

            // 升级迁移：旧版本里家长在电视上勾过的应用会永久盖住 apps.entries。
            // 新版改成「清单为准」，升级后清一次陈旧勾选，让网页里加的应用立刻出现。
            if (!Store.autoRefreshMigrated) {
                val cachedCfg = Store.cachedPlaylist?.let {
                    runCatching { PlaylistParser.parse(it).autoRefresh }.getOrNull()
                } ?: AutoRefresh()
                if (cachedCfg.followAppList) {
                    Extras.clearSelectedApps()
                    Log.i(TAG, "升级迁移：已清除电视本地应用勾选，改为以清单 apps.entries 为准")
                }
                Store.autoRefreshMigrated = true
            }
        }
    }

    fun register(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun unregister(l: Listener) {
        listeners.remove(l)
    }

    /** 把「距上次检查」清零，下一次 1 秒 tick 就会立刻查（从播放页/锁屏回来时用） */
    fun nudge() {
        lastCheckAt = 0L
    }

    /** 由 KidApp 每秒调用；只有到了间隔才真正发起一次后台检查 */
    fun tick() {
        if (!Store.autoRefreshEnabled) return
        // 播放期间不碰 NAS：同一条 SMB 连接被轮询抢走会让孩子看着看着卡住。
        // 退出播放时 PlayerActivity 会 nudge()，更新不会丢。
        if (SmbPlaybackContext.playing) return
        val now = System.currentTimeMillis()
        val base = Store.autoRefreshIntervalMs.coerceIn(5_000L, 3_600_000L)
        // 连续失败时指数退避，最多 5 分钟，避免 NAS 关机时反复打认证
        val interval = base * (1 shl failStreak.coerceAtMost(3)).toLong()
        if (now - lastCheckAt < interval.coerceAtMost(300_000L)) return
        lastCheckAt = now
        checkNow("poll")
    }

    /** 立即检查一次（广播、家长菜单、页面恢复）。并发时直接丢弃，靠下一次兜底 */
    fun checkNow(reason: String, onDone: (() -> Unit)? = null) {
        // 正在播放就先记着，等退出播放页的 nudge() 再来，别和播放抢 SMB 连接
        if (SmbPlaybackContext.playing) {
            lastCheckAt = 0L
            onDone?.invoke()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            onDone?.invoke()
            return
        }
        workers.execute {
            try {
                doCheck(reason)
            } catch (e: Exception) {
                Log.w(TAG, "自动检查异常: ${e.message}")
            } finally {
                busy.set(false)
                onDone?.invoke()
            }
        }
    }

    private fun doCheck(reason: String) {
        val cfg = Store.nas() ?: Store.DEFAULT_NAS
        val share = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE

        val text = try {
            // 独立连接读：正在保存中的 tv.json 可能让 Samba 卡住读端，
            // 挂死在这上面会把整条视频连接一起带走（全站无法播放）
            Smb.readAllIsolated(cfg, share, Store.playlistPath, 12_000)
                .toString(Charsets.UTF_8).trimStart('\uFEFF')
        } catch (e: Exception) {
            failStreak++
            Log.w(TAG, "轮询读清单失败（第 $failStreak 次，静默退避）: ${e.javaClass.simpleName}")
            return
        }
        failStreak = 0

        // 与本地缓存逐字比较：内容一样就绝不重绘，编辑器重写同样内容也不会打扰孩子
        if (text == Store.cachedPlaylist) return

        val fresh = try {
            PlaylistParser.parse(text)
        } catch (e: Exception) {
            Log.w(TAG, "NAS 上的清单不是合法 JSON，忽略本次更新: ${e.message}")
            return
        }

        // 家长在网页里改了应用列表 → 清掉电视本地勾选，让 entries 生效。
        // 只改片单标题/条目时不动本地勾选，避免把家长在电视上的微调冲掉。
        // 本机刚在「家长控制」里写过清单的，也不清：那正是家长自己刚勾好的结果。
        val oldAppsSig = Store.playlistAppsSignature
        val newAppsSig = appsSignature(fresh.apps)
        val follow = fresh.autoRefresh.followAppList
        val justWrittenHere = System.currentTimeMillis() - Store.lastLocalPlaylistWrite < 60_000L
        if (follow && !justWrittenHere && oldAppsSig.isNotBlank() && oldAppsSig != newAppsSig) {
            Extras.clearSelectedApps()
            Log.i(TAG, "清单 apps 段变化，已清除电视本地应用勾选")
        }

        // 复用完整加载链路：写缓存、记 last_sync、展开 auto 分区、同步 NAS 配置
        val res = Repo.load()
        if (res.error != null) {
            // 读到了新文件却解析/展开失败：不写基线，下次继续重试
            Log.w(TAG, "清单已变化但完整加载失败，保留旧画面: ${res.error}")
            return
        }

        val applied = res.playlist
        applyToLocal(applied)
        Store.playlistAppsSignature = appsSignature(applied.apps)
        Store.autoRefreshLastApplied = System.currentTimeMillis()
        Log.i(TAG, "清单已自动更新（$reason）：${applied.sections.size} 个分区 / " +
            "${applied.sections.sumOf { it.items.size }} 个条目")

        main.post {
            listeners.forEach {
                try {
                    it.onPlaylistChanged(applied, reason)
                } catch (e: Exception) {
                    Log.w(TAG, "界面刷新回调失败: ${e.message}")
                }
            }
        }
    }
}
