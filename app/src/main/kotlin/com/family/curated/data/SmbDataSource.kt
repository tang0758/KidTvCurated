package com.family.curated.data

import android.net.Uri
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
}

/**
 * 让 ExoPlayer 直接从 SMB 读数据。
 * 依赖 smbj 的按偏移读取，seek 时无需重新拉整个文件。
 */
class SmbDataSource : BaseDataSource(false) {

    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

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

        val f = try {
            Smb.open(cfg, path)
        } catch (e: Exception) {
            throw IOException("打开文件失败: $path", e)
        }
        file = f
        position = dataSpec.position
        val total = f.sizeBytes()

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total >= 0 -> total - dataSpec.position
            else -> Long.MAX_VALUE
        }
        opened = true
        transferStarted(dataSpec)
        return if (bytesRemaining == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: throw IOException("数据源已关闭")

        val toRead = if (bytesRemaining == Long.MAX_VALUE) length
        else minOf(length.toLong(), bytesRemaining).toInt()

        val n = try {
            synchronized(f) { f.read(buffer, position, offset, toRead) }
        } catch (e: Exception) {
            throw IOException("读取失败 @$position", e)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT

        position += n
        if (bytesRemaining != Long.MAX_VALUE) bytesRemaining -= n
        bytesTransferred(n)
        return n
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
