package com.family.curated.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import android.widget.ImageView
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 从 NAS 读封面图。小内存机器：解码时采样到显示尺寸，缓存上限 24MB。
 *
 * OPT-13：找不到图不再返回 null 让卡片变灰块，而是画一张「渐变底色 + 标题大字」的
 * 占位图，进**同一个** LRU。零 SMB 请求、零额外内存峰值，也不会因为反复探测缺图
 * 而在快速滚动时把单线程封面队列堵死。
 */
object Covers {

    // 单线程：smbj 的 DiskShare 不是线程安全的，多个封面线程同时读同一条连接
    // 会把会话搞坏。封面慢一点无所谓，播放断了才是大事。
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "covers") }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val noCover: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun cached(item: Item): Bitmap? = cache.get(item.key())

    fun markNoCover(item: Item) {
        noCover.add(item.key())
    }

    fun isKnownMissing(item: Item): Boolean = noCover.contains(item.key())

    /** 已缓存就用缓存，否则现画一张并收进 LRU。NAS 还没配好时首页也用它，不再是灰块 */
    fun cachedOrPlaceholder(item: Item, w: Int, h: Int): Bitmap =
        cached(item) ?: placeholder(item, w, h).also { cache.put(item.key(), it) }

    /**
     * 画占位封面。色相由条目 key 派生，同一个视频每次都是同一个颜色；
     * 尺寸按卡片实际大小给，避免生成一张 1080p 大图塞进 24MB 缓存。
     */
    fun placeholder(item: Item, w: Int, h: Int): Bitmap {
        val width = w.coerceIn(64, 640)
        val height = h.coerceIn(64, 900)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)

        val hue = PlaceholderMath.hueOf(item.key()).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.44f, 0.46f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 26f) % 360f, 0.58f, 0.18f))
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                top, bottom, Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

        // 右下角几圈很淡的同心圆，纯渐变在电视上太平了
        val deco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (width / 110f).coerceAtLeast(1f)
            color = Color.argb(36, 255, 255, 255)
        }
        for (i in 1..3) {
            c.drawCircle(width * 0.88f, height * 0.84f, width * 0.14f * i, deco)
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = (width / 8.2f).coerceIn(15f, 42f)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 0f, 2f, Color.argb(160, 0, 0, 0))
        }
        val rows = PlaceholderMath.lines(item.title)
        val lineH = text.textSize * 1.35f
        val baseline = height / 2f - (rows.size - 1) * lineH / 2f -
            (text.ascent() + text.descent()) / 2f
        rows.forEachIndexed { i, s ->
            c.drawText(s, width / 2f, baseline + i * lineH, text)
        }
        return bmp
    }

    /**
     * 异步加载封面。找到图回调真封面；找不到回调**占位图**（不再是 null）。
     */
    fun load(iv: ImageView, cfg: NasConfig, item: Item, targetW: Int, targetH: Int, onLoaded: (Bitmap?) -> Unit) {
        val tag = item.key()
        iv.tag = tag

        cached(item)?.let {
            onLoaded(it)
            return
        }
        if (noCover.contains(tag)) {
            onLoaded(cachedOrPlaceholder(item, targetW, targetH))
            return
        }

        // 播放中不抢 SMB 连接：隔一会儿再试，退出播放页后自然补上真封面
        if (SmbPlaybackContext.playing) {
            retryAfterPlayback(iv, cfg, item, targetW, targetH, onLoaded, 0)
            return
        }

        pool.execute {
            var bmp: Bitmap? = null
            for (candidate in Repo.coverCandidates(item)) {
                try {
                    // 用独立短连接读图：封面只是装饰，绝不能因为某张图读挂而触发
                    // 主连接的 watchdog 重置（那会把正在进行的剧集列表/播放一起弄死）
                    val bytes = Smb.readAllIsolated(
                        cfg, candidate.share ?: cfg.share, candidate.path, 8_000
                    )
                    if (bytes.isEmpty()) continue
                    bmp = decode(bytes, targetW, targetH)
                    if (bmp != null) break
                } catch (e: Exception) {
                    // 文件不存在是常态，静默跳过
                }
            }
            val finalBmp = bmp ?: placeholder(item, targetW, targetH)
            if (bmp == null) noCover.add(tag)
            cache.put(tag, finalBmp)
            iv.post {
                if (iv.tag == tag) onLoaded(finalBmp)
            }
        }
    }

    /** 播放中先挂起，每 1.5 秒探一次，退出播放后自动补封面；一直腾不出连接就先给占位图 */
    private fun retryAfterPlayback(
        iv: ImageView, cfg: NasConfig, item: Item,
        targetW: Int, targetH: Int, onLoaded: (Bitmap?) -> Unit, attempt: Int
    ) {
        if (attempt >= 8) {
            noCover.add(item.key())
            onLoaded(cachedOrPlaceholder(item, targetW, targetH))
            return
        }
        main.postDelayed({
            if (iv.tag != item.key()) return@postDelayed
            if (SmbPlaybackContext.playing) {
                retryAfterPlayback(iv, cfg, item, targetW, targetH, onLoaded, attempt + 1)
            } else {
                load(iv, cfg, item, targetW, targetH, onLoaded)
            }
        }, 1_500L)
    }

    private fun decode(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val sample = calcSample(opts.outWidth, opts.outHeight, reqW, reqH)
        val real = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun calcSample(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        val tw = reqW.coerceAtLeast(1)
        val th = reqH.coerceAtLeast(1)
        while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
        return s
    }

    /** 清缓存也清「缺图」记忆：家长补了封面后，用「清空所有播放进度」即可重新探测 */
    fun clear() {
        cache.evictAll()
        noCover.clear()
    }
}
