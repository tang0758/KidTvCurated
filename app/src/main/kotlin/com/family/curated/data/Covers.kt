package com.family.curated.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 从 NAS 读封面图。小内存机器：解码时采样到显示尺寸，缓存上限 24MB。
 */
object Covers {

    private val pool = Executors.newFixedThreadPool(3)

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val noCover: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun cached(item: Item): Bitmap? = cache.get(item.key())

    fun markNoCover(item: Item) {
        noCover.add(item.key())
    }

    fun isKnownMissing(item: Item): Boolean = noCover.contains(item.key())

    /**
     * 异步加载封面。找到图后回调 onLoaded(bitmap)，找不到回调 onLoaded(null)。
     */
    fun load(iv: ImageView, cfg: NasConfig, item: Item, targetW: Int, targetH: Int, onLoaded: (Bitmap?) -> Unit) {
        val tag = item.key()
        iv.tag = tag

        cached(item)?.let {
            onLoaded(it)
            return
        }
        if (noCover.contains(tag)) {
            onLoaded(null)
            return
        }

        pool.execute {
            var bmp: Bitmap? = null
            for (candidate in Repo.coverCandidates(item)) {
                try {
                    val bytes = Smb.readAll(cfg, candidate, 12 * 1024 * 1024)
                    if (bytes.isEmpty()) continue
                    bmp = decode(bytes, targetW, targetH)
                    if (bmp != null) break
                } catch (e: Exception) {
                    // 文件不存在是常态，静默跳过
                }
            }
            if (bmp != null) {
                cache.put(tag, bmp)
            } else {
                noCover.add(tag)
            }
            iv.post {
                if (iv.tag == tag) onLoaded(bmp)
            }
        }
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

    fun clear() {
        cache.evictAll()
        noCover.clear()
    }
}
