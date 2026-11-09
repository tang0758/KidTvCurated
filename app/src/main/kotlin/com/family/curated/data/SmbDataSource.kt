package com.family.curated.data

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException
import java.net.URLDecoder

/** 播放时使用的 NAS 配置（由播放页设置） */
object SmbPlaybackContext {
    @Volatile
    var config: NasConfig? = null

    /**
     * 是否正在播放。
     *
     * 电视只有一个网卡、一条 SMB 连接，播放时再让清单轮询 / 封面探测 / 网段扫描
     * 去抢这条连接，轻则卡顿，重则把 smbj 的会话搞坏（表现为「所有视频都打不开，
     * 重启 App 才好」）。播放期间这些后台活儿一律让路。
     */
    @Volatile
    var playing: Boolean = false
}

/**
 * 让 ExoPlayer 直接从 SMB 读数据。
 * 依赖 smbj 的按偏移读取，seek 时无需重新拉整个文件。
 */
class SmbDataSource : BaseDataSource(false) {

    companion object {
        private const val TAG = "SmbDataSource"
        private const val READ_ATTEMPTS = 3
    }

    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private var openedPath: String = ""

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val cfg = SmbPlaybackContext.config ?: throw IOException("未配置 NAS")

        val raw = dataSpec.uri.path ?: throw IOException("路径为空")
        val path = try {
            URLDecoder.decode(raw.trimStart('/'), "UTF-8")
        } catch (e: Exception) {
            raw.trimStart('/')
        }
        openedPath = path

        // 打开失败在这里就先自愈掉，别让 ExoPlayer 把一次 SMB 抖动误判成「格式不支持」
        val f = try {
            Smb.openRetrying(cfg, path)
        } catch (e: Exception) {
            Log.e(TAG, "open 最终失败: $path", e)
            throw IOException("打开文件失败: $path", e)
        }
        file = f
        position = dataSpec.position
        var total = f.sizeBytes()
        if (total <= 0L) {
            try { Thread.sleep(200) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            total = f.sizeBytes()
        }
        if (total <= 0L) {
            try { f.close() } catch (e: Exception) { }
            file = null
            throw IOException("文件大小读取异常($total)，可能连接被中断: $path")
        }

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> total - dataSpec.position
        }
        opened = true
        transferStarted(dataSpec)
        return if (bytesRemaining == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else bytesRemaining
    }

    /**
     * 按偏移读取。读失败时先从同一个偏移重读，再不行就重新打开文件（会重建 SMB 连接）
     * 后继续读；位置不变，所以不会跳帧。播放中的网络抖动因此大多能自己恢复。
     */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        var f = file ?: throw IOException("数据源已关闭")

        val toRead = if (bytesRemaining == Long.MAX_VALUE) length
        else minOf(length.toLong(), bytesRemaining).toInt()
        val at = position
        var last: Exception? = null

        for (attempt in 0 until READ_ATTEMPTS) {
            try {
                val n = synchronized(f) { f.read(buffer, at, offset, toRead) }
                if (n <= 0) return C.RESULT_END_OF_INPUT
                position = at + n
                if (bytesRemaining != Long.MAX_VALUE) bytesRemaining -= n
                bytesTransferred(n)
                return n
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "read ${attempt + 1}/$READ_ATTEMPTS 失败 @$at: ${e.javaClass.simpleName}: ${e.message}")
                if (attempt == READ_ATTEMPTS - 1) break
                try { Thread.sleep(200L * (attempt + 1)) } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                }
                // 最后一次重试前换一个新句柄，顺带把断掉的连接重建起来
                if (attempt >= 1) {
                    try { f.close() } catch (e2: Exception) { }
                    try {
                        val cfg = SmbPlaybackContext.config ?: throw IOException("未配置 NAS")
                        f = Smb.openRetrying(cfg, openedPath, 2)
                        file = f
                    } catch (e2: Exception) {
                        last = e2
                    }
                }
            }
        }
        throw IOException("读取失败 @$at（已重试 $READ_ATTEMPTS 次）: $openedPath", last)
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { file?.close() } catch (e: Exception) { }
        file = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}
