package com.family.curated.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 名画图片加载：
 *  - asset:xxx  → APK 内置的公共领域名画（离线可用）
 *  - smb:xxx / 裸路径 → NAS 上家长自己放的图片
 *  - learning.artFolder 配了目录时，目录里的图片按顺序覆盖内置名画
 *
 * 电视内存小：解码前采样到实际显示尺寸，缓存上限 12MB，只留一条后台线程。
 */
object ArtImages {

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "art-images").apply { isDaemon = true }
    }
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXT && !name.startsWith(".")

    /**
     * 把 NAS 图片目录里的文件按顺序贴到名画卡片上。
     * 会连 SMB，请在后台线程调用。
     */
    fun bindNasArt(deck: List<LearningCard>, learning: Learning, cfg: NasConfig?): List<LearningCard> {
        val folder = learning.artFolder.trim().trimEnd('/')
        if (folder.isBlank() || cfg == null) return deck
        val files = try {
            Smb.list(cfg, folder, learning.artShare.ifBlank { null })
                .filter { !it.second && isImage(it.first) }
                .map { it.first }
                .sortedWith(NaturalOrder)
        } catch (e: Exception) {
            return deck
        }
        if (files.isEmpty()) return deck

        var n = 0
        return deck.map { c ->
            if (c.type == CardType.ART) {
                val f = files[n % files.size]
                n++
                c.copy(image = "smb:$folder/$f")
            } else c
        }
    }

    /** 异步加载，回调在主线程；找不到或读失败时回调 null */
    fun load(
        ctx: Context,
        iv: ImageView,
        card: LearningCard,
        cfg: NasConfig?,
        artShare: String,
        reqW: Int,
        reqH: Int,
        onLoaded: (Bitmap?) -> Unit
    ) {
        val image = card.image
        val tag = image ?: "none"
        iv.tag = tag
        if (image.isNullOrBlank() || missing.contains(tag)) {
            onLoaded(null)
            return
        }
        cache.get(tag)?.let {
            onLoaded(it)
            return
        }

        pool.execute {
            val bytes: ByteArray? = try {
                when {
                    image.startsWith("asset:") ->
                        ctx.applicationContext.assets.open("art/" + image.removePrefix("asset:"))
                            .use { it.readBytes() }
                    cfg != null -> {
                        val p = image.removePrefix("smb:").trimStart('/')
                        Smb.readAll(cfg, p, 8 * 1024 * 1024, artShare.ifBlank { null })
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            val bmp = bytes?.let { decode(it, reqW, reqH) }
            if (bmp != null) cache.put(tag, bmp) else missing.add(tag)
            iv.post {
                if (iv.tag == tag) onLoaded(bmp)
            }
        }
    }

    private fun decode(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val tw = reqW.coerceAtLeast(1)
        val th = reqH.coerceAtLeast(1)
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun clear() {
        cache.evictAll()
        missing.clear()
    }
}
