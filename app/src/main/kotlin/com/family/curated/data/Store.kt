package com.family.curated.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地存储：NAS 配置、清单缓存、播放进度、"今天看过"记录。
 */
object Store {

    private const val PREF = "curated_prefs"
    private const val KEY_MIGRATED = "migrated_path_keys_v1"
    private lateinit var sp: SharedPreferences

    const val DEFAULT_PLAYLIST_PATH = "curated/tv.json"
    const val DEFAULT_PLAYLIST_SHARE = "data"

    /** 首次启动的默认 NAS；清单里的 nas 段会自动覆盖 */
    val DEFAULT_NAS = NasConfig(
        host = "192.168.31.72",
        port = 445,
        user = LocalSecrets.USER,
        password = LocalSecrets.PASS,
        share = "儿童资源"
    )

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    // ---------- NAS ----------
    fun saveNas(c: NasConfig) {
        // 片单里的密码可能是占位符（空、全星号、编辑器的 __KEEP_...__）。
        // 这种情况保留上一次能用的密码，否则一次误保存会让电视再也连不上 NAS，
        // 而且因为连不上就读不到新片单，自己永远恢复不了。
        val old = sp.getString("nas_pass", "") ?: ""
        val pass = if (isPlaceholderPassword(c.password)) old else c.password
        sp.edit()
            .putString("nas_host", c.host)
            .putInt("nas_port", c.port)
            .putString("nas_user", c.user)
            .putString("nas_pass", pass)
            .putString("nas_share", c.share)
            .apply()
    }

    private fun isPlaceholderPassword(p: String?): Boolean {
        val s = (p ?: "").trim()
        if (s.isEmpty()) return true
        if (s.uppercase().contains("KEEP_NAS_PASSWORD")) return true
        return s.length >= 3 && s.all { it == '*' || it == '•' }
    }

    fun nas(): NasConfig? {
        val host = sp.getString("nas_host", null) ?: return null
        return NasConfig(
            host = host,
            port = sp.getInt("nas_port", 445),
            user = sp.getString("nas_user", "") ?: "",
            password = sp.getString("nas_pass", "") ?: "",
            share = sp.getString("nas_share", "") ?: ""
        )
    }

    var playlistPath: String
        get() = sp.getString("playlist_path", DEFAULT_PLAYLIST_PATH) ?: DEFAULT_PLAYLIST_PATH
        set(v) = sp.edit().putString("playlist_path", v).apply()

    /** 上次成功读到清单的共享名。默认放在独立的 data 共享里 */
    var playlistShare: String?
        get() = sp.getString("playlist_share", DEFAULT_PLAYLIST_SHARE) ?: DEFAULT_PLAYLIST_SHARE
        set(v) = sp.edit().putString("playlist_share", v).apply()

    var cachedPlaylist: String?
        get() = sp.getString("playlist_cache", null)
        set(v) = sp.edit().putString("playlist_cache", v).apply()

    /** 记住上次成功读取清单的时间，用于判断缓存是否过期 */
    var lastSync: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    // ---------- 清单自动刷新 ----------
    /** 升级到「清单为准」策略的一次性迁移：清掉陈旧的电视本地勾选 */
    var autoRefreshMigrated: Boolean
        get() = sp.getBoolean("auto_refresh_migrated", false)
        set(v) = sp.edit().putBoolean("auto_refresh_migrated", v).apply()

    /** 上次成功加载的 apps 段指纹；清单里应用列表变了就让 entries 重新生效 */
    var playlistAppsSignature: String
        get() = sp.getString("playlist_apps_sig", "") ?: ""
        set(v) = sp.edit().putString("playlist_apps_sig", v).apply()

    /** 轮询配置缓存：广播把进程拉起时不必重新解析整份 JSON */
    var autoRefreshEnabled: Boolean
        get() = sp.getBoolean("auto_refresh_enabled", true)
        set(v) = sp.edit().putBoolean("auto_refresh_enabled", v).apply()

    var autoRefreshIntervalMs: Long
        get() = sp.getLong("auto_refresh_interval_ms", 15_000L)
        set(v) = sp.edit().putLong("auto_refresh_interval_ms", v).apply()

    /** 最近一次「检测到 NAS 清单变化并成功应用」的时间，供网页诊断 */
    var autoRefreshLastApplied: Long
        get() = sp.getLong("auto_refresh_last_applied", 0L)
        set(v) = sp.edit().putLong("auto_refresh_last_applied", v).apply()

    /**
     * 最近一次本机（家长控制页）写 tv.json 的时间。
     * 自动同步发现 apps 段变了时默认会清掉电视上的应用勾选（让网页的 entries 生效），
     * 但如果是本机刚写的，就得留着家长刚刚勾好的结果。
     */
    var lastLocalPlaylistWrite: Long
        get() = sp.getLong("local_playlist_write", 0L)
        set(v) = sp.edit().putLong("local_playlist_write", v).apply()

    // ---------- 远程控制卡 control.json（OPT-06） ----------

    /** 最近一次执行过的指令幂等键（control.json 的 updatedAt） */
    var lastControlAt: String
        get() = sp.getString("last_control_at", "") ?: ""
        set(v) = sp.edit().putString("last_control_at", v).apply()

    // ---------- 未同步的设置修改（OPT-07） ----------
    //
    // 写 NAS 失败时，家长以为改好了、其实 NAS 上还是旧内容。这个标记让：
    //  - 设置页一直挂一条红色错误条，不静默回滚；
    //  - PlaylistSync 在下发设置前先问家长，不把「上次没保存成功」的事实抹掉；
    //  - 「重试」能用当时生成的完整 tv.json 原样覆盖回去。

    var hasUnsyncedEdit: Boolean
        get() = sp.getBoolean("unsynced_edit", false)
        set(v) = sp.edit().putBoolean("unsynced_edit", v).apply()

    /** 失败时本想写入的完整 tv.json；读 NAS 就失败时为空（没有可重放的内容） */
    var pendingPlaylistJson: String?
        get() = sp.getString("unsynced_json", null)
        set(v) = sp.edit().putString("unsynced_json", v).apply()

    /** 失败前读到的远端内容指纹；覆盖前比对，避免抹掉家长后来在网页上做的改动 */
    var pendingBaseHash: String
        get() = sp.getString("unsynced_base", "") ?: ""
        set(v) = sp.edit().putString("unsynced_base", v).apply()

    var unsyncedLabel: String
        get() = sp.getString("unsynced_label", "设置") ?: "设置"
        set(v) = sp.edit().putString("unsynced_label", v).apply()

    var unsyncedMessage: String
        get() = sp.getString("unsynced_message", "") ?: ""
        set(v) = sp.edit().putString("unsynced_message", v).apply()

    var unsyncedAt: Long
        get() = sp.getLong("unsynced_at", 0L)
        set(v) = sp.edit().putLong("unsynced_at", v).apply()

    fun markUnsynced(label: String, pending: String?, baseHash: String, message: String) {
        sp.edit()
            .putBoolean("unsynced_edit", true)
            .putString("unsynced_json", pending)
            .putString("unsynced_base", baseHash)
            .putString("unsynced_label", label)
            .putString("unsynced_message", message)
            .putLong("unsynced_at", System.currentTimeMillis())
            .apply()
    }

    fun clearUnsynced() {
        sp.edit()
            .remove("unsynced_edit")
            .remove("unsynced_json")
            .remove("unsynced_base")
            .remove("unsynced_label")
            .remove("unsynced_message")
            .remove("unsynced_at")
            .apply()
    }

    // ---------- 播放进度 ----------
    fun progress(key: String): Long = sp.getLong("pos_$key", 0L)

    fun setProgress(key: String, ms: Long) {
        sp.edit().putLong("pos_$key", ms.coerceAtLeast(0L)).apply()
    }

    fun duration(key: String): Long = sp.getLong("dur_$key", 0L)

    fun setDuration(key: String, ms: Long) {
        sp.edit().putLong("dur_$key", ms.coerceAtLeast(0L)).apply()
    }

    // ---------- 最近看过（OPT-12 首页「继续看」） ----------
    /**
     * 记一次「真的点开看过」。不复用 setProgress 的写入时间：进度在暂停、退出、
     * 自动重连时都会落盘，拿它当「最近看」会把刚翻过的老片刷到最前面。
     */
    fun notePlayed(key: String) {
        if (key.isBlank()) return
        sp.edit().putLong("played_$key", System.currentTimeMillis()).apply()
    }

    /** 按最近点开时间倒序，多取一些交给 ContinueMath 过滤掉已看完 / 已不在片单的 */
    fun recentPlayed(limit: Int = 30): List<String> =
        sp.all.entries
            .mapNotNull { (k, v) ->
                if (k.startsWith("played_") && v is Long) k.removePrefix("played_") to v else null
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    // ---------- 今日完成 ----------
    // 走 ClockGuard：这一套 done_ 键也是「日期派生」的，改电视日期同样不该让它清零
    fun today(): String = ClockGuard.day()

    fun markDone(key: String) {
        sp.edit().putString("done_$key", today()).apply()
    }

    fun unmarkDone(key: String) {
        sp.edit().remove("done_$key").apply()
    }

    fun isDoneToday(key: String): Boolean = sp.getString("done_$key", null) == today()

    fun clearTodayRecursive() {
        val t = today()
        val e = sp.edit()
        sp.all.forEach { (k, v) ->
            if (k.startsWith("done_") && v == t) e.remove(k)
        }
        e.apply()
    }

    /**
     * 一次性迁移：老版本用「分区名_序号」当条目 id（位置型），家长在编辑器里调一次顺序，
     * 封面缓存、观看进度、"今天看过"就全错位。现在改成按路径生成稳定 id，
     * 这里把旧的 pos_/dur_/done_ 记录搬到新键上，免得升级当天"必修"清零。
     */
    fun migrateLegacyKeys(pl: Playlist) {
        if (sp.getBoolean(KEY_MIGRATED, false)) return
        val e = sp.edit()
        var moved = 0
        pl.sections.forEach { sec ->
            sec.items.forEachIndexed { idx, it ->
                val legacy = sec.key + "_" + idx
                val now = it.key()
                if (now == legacy) return@forEachIndexed
                for (p in listOf("pos_", "dur_", "done_")) {
                    val oldKey = p + legacy
                    val newKey = p + now
                    if (!sp.contains(oldKey) || sp.contains(newKey)) continue
                    when (p) {
                        "done_" -> {
                            val v = sp.getString(oldKey, null) ?: continue
                            e.putString(newKey, v)
                        }
                        else -> e.putLong(newKey, sp.getLong(oldKey, 0L))
                    }
                    moved++
                }
            }
        }
        e.putBoolean(KEY_MIGRATED, true)
        e.apply()
        if (moved > 0) android.util.Log.i("Store", "已迁移旧版位置型进度/完成记录 $moved 条")
    }

    /** 家长手动把某项标记为未完成（偶尔用来"重来一遍"） */
    fun resetAllProgress() {
        val e = sp.edit()
        sp.all.keys.forEach { k ->
            if (k.startsWith("done_") || k.startsWith("pos_") || k.startsWith("dur_") ||
                k.startsWith("played_")) e.remove(k)
        }
        e.apply()
    }

    /** 每日轮换的起点日期（首次运行那天），返回 yyyy-MM-dd */
    fun rotationStart(): String {
        val saved = sp.getString("rotation_start", null)
        if (saved != null) return saved
        val t = today()
        sp.edit().putString("rotation_start", t).apply()
        return t
    }

    /** 从起点到今天过了多少天（含今天为 0） */
    fun daysSince(startYmd: String): Int {
        return try {
            val p = startYmd.split("-")
            val c = java.util.Calendar.getInstance()
            c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 0, 0, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            val startMs = c.timeInMillis
            val now = java.util.Calendar.getInstance()
            val todayMs = now.clone() as java.util.Calendar
            todayMs.set(java.util.Calendar.HOUR_OF_DAY, 0)
            todayMs.set(java.util.Calendar.MINUTE, 0)
            todayMs.set(java.util.Calendar.SECOND, 0)
            todayMs.set(java.util.Calendar.MILLISECOND, 0)
            ((todayMs.timeInMillis - startMs) / 86_400_000L).toInt().coerceAtLeast(0)
        } catch (e: Exception) {
            0
        }
    }
}
