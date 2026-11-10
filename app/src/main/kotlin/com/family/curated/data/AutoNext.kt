package com.family.curated.data

import java.net.URLDecoder
import java.net.URLEncoder

/** 自动连播队列里的一集。key 必须是路径派生，不能用下标（见 Store.migrateLegacyKeys 的教训） */
data class QueueItem(val file: String, val title: String, val key: String)

/**
 * OPT-11 自动连播的纯逻辑部分（可单测，不碰 Android）。
 *
 * 设计取舍：剧集页把「这一季的文件序列」整串交给播放页，播放页结束后只做数组取下一个，
 * **不再去 SMB 列目录**。原因有两个：
 *  1. 一集刚播完时 SMB 连接最容易处于半死状态，这时候列目录会卡住整个播放链；
 *  2. 播放期间主连接本来就要全留给视频读（SmbPlaybackContext.playing），不该被后台任务抢。
 *
 * 队列用 URL 编码的纯文本，而不是 org.json：后者在纯 JVM 单测里是 Android 桩类，
 * 一调用就抛 RuntimeException，连播这么关键的闸门必须有真正跑得起来的单测。
 */
object AutoNext {
    const val EXTRA_QUEUE = "queue"
    const val EXTRA_QUEUE_INDEX = "queueIndex"
    const val COUNTDOWN_SECONDS = 5

    /** 队列最多带这么多集，防止单个文件夹几百集把 Intent 撑爆（Binder 上限约 1MB） */
    const val MAX_QUEUE = 200

    fun encode(items: List<Item>): String = buildString {
        items.take(MAX_QUEUE).forEach {
            val f = it.file ?: return@forEach
            if (isNotEmpty()) append('\n')
            append(enc(f)).append('|').append(enc(it.title)).append('|').append(enc(it.key()))
        }
    }

    /** 坏数据一律「当作没有下一集」，绝不让一条脏队列把播放页带崩 */
    fun decode(raw: String?): List<QueueItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<QueueItem>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val parts = line.split('|')
            if (parts.size < 3) continue
            val f = dec(parts[0])
            if (f.isBlank()) continue
            out.add(
                QueueItem(
                    file = f,
                    title = dec(parts[1]).ifBlank { f.substringAfterLast('/') },
                    key = dec(parts[2]).ifBlank { f }
                )
            )
        }
        return out
    }

    fun next(queue: List<QueueItem>, index: Int): QueueItem? =
        if (index in 0 until queue.size - 1) queue[index + 1] else null

    /**
     * 三道闸门缺一不可：功能开关、全局门禁（时段 / 限额 / 远程锁定都算）、确实还有下一集。
     * 家长在倒计时里按了取消，也立即作废。
     */
    fun allowed(featureOn: Boolean, gateOpen: Boolean, hasNext: Boolean, cancelled: Boolean): Boolean =
        featureOn && gateOpen && hasNext && !cancelled

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (e: Exception) {
        ""
    }

    private fun dec(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        ""
    }
}
