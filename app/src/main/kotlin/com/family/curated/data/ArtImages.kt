package com.family.curated.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File
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

    // 线程带上名字和 daemon：不给电视留下「App 退出了线程还在」的尾巴，排障时也认得出是谁
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "art-images").apply { isDaemon = true }
    }
    // 独立低优先级预加载线程：绝不阻塞当前前台卡片加载
    private val prefetchPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "art-prefetch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
    }
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // 以前是 HashSet + synchronized(missing)：加载线程写、主线程 clear() 读，
    // 中间隔着 SMB 的等待，锁不住就抛 ConcurrentModificationException
    private val missing: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val inflight: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
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
            val bytes = fetchBytes(ctx.applicationContext, image, cfg, artShare)
            val bmp = bytes?.let { decode(it, reqW, reqH) }
            if (bmp != null) {
                cache.put(tag, bmp)
            } else if (image.startsWith("asset:")) {
                missing.add(tag)
            }
            iv.post {
                if (iv.tag == tag) onLoaded(bmp)
            }
        }
    }

    /**
     * 后台预加载相邻卡片图片：翻到下一张时直接命中 LruCache（0ms 同步出图）。
     * 先查本地磁盘缓存（cacheDir/card_images），未命中再走 SMB 并落盘。
     */
    fun prefetch(
        ctx: Context,
        cards: List<LearningCard>,
        cfg: NasConfig?,
        artShare: String
    ) {
        val appCtx = ctx.applicationContext
        cards.forEach { card ->
            val image = card.image?.takeIf { it.isNotBlank() } ?: return@forEach
            if (missing.contains(image) || cache.get(image) != null) return@forEach
            if (!inflight.add(image)) return@forEach
            val reqW = if (card.type == CardType.ART) 800 else 400
            val reqH = if (card.type == CardType.ART) 480 else 400
            prefetchPool.execute {
                try {
                    if (cache.get(image) != null) return@execute
                    val bytes = fetchBytes(appCtx, image, cfg, artShare)
                    val bmp = bytes?.let { decode(it, reqW, reqH) }
                    if (bmp != null) {
                        cache.put(image, bmp)
                    } else if (image.startsWith("asset:")) {
                        missing.add(image)
                    }
                } finally {
                    inflight.remove(image)
                }
            }
        }
    }

    private fun fetchBytes(
        appCtx: Context,
        image: String,
        cfg: NasConfig?,
        artShare: String
    ): ByteArray? = try {
        when {
            image.startsWith("asset:") ->
                appCtx.assets.open("art/" + image.removePrefix("asset:")).use { it.readBytes() }
            cfg != null -> {
                val diskDir = File(appCtx.cacheDir, "card_images")
                val hash = RemoteCommand.sha256Hex(image.toByteArray(Charsets.UTF_8)).take(24)
                val diskFile = File(diskDir, "$hash.img")
                if (diskFile.exists() && diskFile.length() > 0L) {
                    diskFile.readBytes()
                } else {
                    val raw = image.removePrefix("smb:").trim()
                    val colon = raw.indexOf(':')
                    val explicitShare = if (colon > 0) raw.substring(0, colon).trim() else ""
                    val relPath = (if (colon > 0) raw.substring(colon + 1) else raw).trimStart('/')
                    val dataShare = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE
                    val sharesToTry = if (explicitShare.isNotEmpty()) {
                        listOf(explicitShare)
                    } else if (relPath.startsWith("curated/") || relPath.startsWith("data-sync/")) {
                        listOf(dataShare, artShare.ifBlank { cfg.share }).distinct()
                    } else {
                        listOf(artShare.ifBlank { cfg.share }, dataShare).distinct()
                    }
                    var loaded: ByteArray? = null
                    for (sh in sharesToTry) {
                        if (sh.isBlank()) continue
                        loaded = try {
                            Smb.readAllIsolated(cfg, sh, relPath, 12_000)
                        } catch (_: Exception) {
                            null
                        }
                        if (loaded != null && loaded.isNotEmpty()) break
                    }
                    if (loaded != null && loaded.isNotEmpty() && loaded.size <= 8 * 1024 * 1024) {
                        try {
                            diskDir.mkdirs()
                            diskFile.writeBytes(loaded)
                        } catch (_: Exception) {
                        }
                    }
                    loaded
                }
            }
            else -> null
        }
    } catch (_: Exception) {
        null
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
