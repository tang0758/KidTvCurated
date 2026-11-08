package com.family.curated.data

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.messages.SMB2QueryInfoResponse
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.ByteArrayOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 极简 SMB2/3 封装。整个 App 只维持一条连接，所有文件操作串行化。
 * 电视内存小，连接数越少越稳。
 */
object Smb {

    private const val TAG = "Smb"

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var current: NasConfig? = null
    private val extraShares = HashMap<String, DiskShare>()

    private val lock = ReentrantLock()

    @Synchronized
    fun ensureConnected(cfg: NasConfig): DiskShare = lock.withLock {
        val existing = share
        if (existing != null && current == cfg && connection?.isConnected == true) {
            return existing
        }
        closeQuietly()

        val conf = SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(45, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build()

        val c = SMBClient(conf)
        val conn = c.connect(cfg.host, cfg.port)
        val ac = AuthenticationContext(cfg.user, cfg.password.toCharArray(), null)
        val sess = conn.authenticate(ac)
        val sh = sess.connectShare(cfg.share) as DiskShare

        client = c
        connection = conn
        session = sess
        share = sh
        current = cfg
        Log.i(TAG, "connected to ${cfg.host}/${cfg.share}")
        return sh
    }

    fun path(p: String): String {
        val cleaned = p.replace('/', '\\').trim('\\')
        return "\\$cleaned"
    }

    /**
     * 连到同一个 NAS 上的另一个共享。
     * 视频共享通常被设成只读，清单就得放在另一个可写共享里。
     */
    @Synchronized
    fun shareNamed(cfg: NasConfig, name: String): DiskShare = lock.withLock {
        if (name == cfg.share) return ensureConnected(cfg)
        ensureConnected(cfg)
        val existing = extraShares[name]
        if (existing != null && connection?.isConnected == true) return existing
        val sh = (session ?: throw IllegalStateException("未连接")).connectShare(name) as DiskShare
        extraShares[name] = sh
        return sh
    }

    /** 列目录：返回 (文件名, 是否目录, 大小)。shareName 可指定同一个 NAS 上的其它共享 */
    fun list(cfg: NasConfig, dir: String, shareName: String? = null): List<Triple<String, Boolean, Long>> {
        val sh = if (shareName.isNullOrBlank() || shareName == cfg.share)
            ensureConnected(cfg) else shareNamed(cfg, shareName)
        val result = ArrayList<Triple<String, Boolean, Long>>()
        sh.list(path(dir)).forEach { f ->
            val name = f.fileName
            if (name == "." || name == "..") return@forEach
            val isDir = EnumWithValue.EnumUtils.isSet(
                f.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY
            )
            result.add(Triple(name, isDir, f.endOfFile))
        }
        Log.d(TAG, "list($dir) -> ${result.size} 项")
        return result
    }

    fun exists(cfg: NasConfig, file: String): Boolean {
        return try {
            ensureConnected(cfg).fileExists(path(file))
        } catch (e: Exception) {
            false
        }
    }

    /** 读取整个文件到内存（用于 json / 图片，勿用于视频） */
    fun readAll(
        cfg: NasConfig,
        file: String,
        maxBytes: Int = 8 * 1024 * 1024,
        shareName: String? = null
    ): ByteArray {
        val sh = if (shareName.isNullOrBlank() || shareName == cfg.share)
            ensureConnected(cfg) else shareNamed(cfg, shareName)
        sh.openFile(
            path(file),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        ).use { f ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var off = 0L
            while (true) {
                val n = f.read(buf, off, 0, buf.size)
                if (n <= 0) break
                out.write(buf, 0, n)
                off += n
                if (out.size() > maxBytes) break
            }
            return out.toByteArray()
        }
    }

    /** 打开文件用于流式读取（视频播放）。调用方负责 close。 */
    fun open(cfg: NasConfig, file: String): SmbFile {
        val sh = ensureConnected(cfg)
        return sh.openFile(
            path(file),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
    }

    fun fileSize(cfg: NasConfig, file: String): Long {
        return try {
            open(cfg, file).use { it.sizeBytes() }
        } catch (e: Exception) {
            -1L
        }
    }

    @Synchronized
    fun closeQuietly() {
        extraShares.values.forEach { try { it.close() } catch (e: Exception) { } }
        extraShares.clear()
        try { share?.close() } catch (e: Exception) { }
        try { session?.close() } catch (e: Exception) { }
        try { connection?.close() } catch (e: Exception) { }
        try { client?.close() } catch (e: Exception) { }
        share = null; session = null; connection = null; client = null; current = null
    }

    fun reset() {
        lock.withLock { closeQuietly() }
    }
}

/** smbj 没有直接的 fileLength()，从文件信息里取 */
fun SmbFile.sizeBytes(): Long = try {
    fileInformation.standardInformation.endOfFile
} catch (e: Exception) {
    -1L
}
