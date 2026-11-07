package com.family.curated.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地存储：NAS 配置、清单缓存、播放进度、"今天看过"记录。
 */
object Store {

    private const val PREF = "curated_prefs"
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
        sp.edit()
            .putString("nas_host", c.host)
            .putInt("nas_port", c.port)
            .putString("nas_user", c.user)
            .putString("nas_pass", c.password)
            .putString("nas_share", c.share)
            .apply()
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

    // ---------- 播放进度 ----------
    fun progress(key: String): Long = sp.getLong("pos_$key", 0L)

    fun setProgress(key: String, ms: Long) {
        sp.edit().putLong("pos_$key", ms.coerceAtLeast(0L)).apply()
    }

    fun duration(key: String): Long = sp.getLong("dur_$key", 0L)

    fun setDuration(key: String, ms: Long) {
        sp.edit().putLong("dur_$key", ms.coerceAtLeast(0L)).apply()
    }

    // ---------- 今日完成 ----------
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

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

    /** 家长手动把某项标记为未完成（偶尔用来"重来一遍"） */
    fun resetAllProgress() {
        val e = sp.edit()
        sp.all.keys.forEach { k ->
            if (k.startsWith("done_") || k.startsWith("pos_") || k.startsWith("dur_")) e.remove(k)
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
