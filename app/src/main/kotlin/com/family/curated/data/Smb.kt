package com.family.curated.data

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
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
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * 兜底超时。
     *
     * smbj 的 socket 超时管不住两种卡死：NAS 踢掉空闲会话后 TCP 半开、
     * 以及 Samba 等客户端回应 oplock break（PC 正在写 tv.json 时最容易撞上）。
     * 这两种情况下读请求会无限期等一个永远不来的响应，而整个 App 只有一条连接、
     * 一把锁 —— 只要一处卡住，主界面、剧集列表、播放就全都没反应，只能重启 App。
     *
     * 所以每个对外操作都套一层硬上限：超时就强制关闭连接（关闭 socket 会让卡住的
     * 读抛异常并释放锁），下一次调用自动重连。
     */
    private val ioPool = Executors.newCachedThreadPool { r ->
        Thread(r, "smb-io").apply { isDaemon = true }
    }

    private fun <T> withDeadline(
        label: String,
        timeoutMs: Long,
        onTimeout: () -> Unit = { closeQuietly() },
        block: () -> T
    ): T {
        val fut = ioPool.submit(Callable { block() })
        return try {
            fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "$label 超过 ${timeoutMs}ms 未返回，判定连接已卡死，强制重置")
            try {
                onTimeout()
            } catch (t: Throwable) {
                Log.w(TAG, "重置连接失败: ${t.javaClass.simpleName}: ${t.message}")
            }
            fut.cancel(true)
            throw IOException("$label 超时（${timeoutMs / 1000} 秒没有响应），已重置 NAS 连接")
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: IOException("$label 失败", e.cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("$label 被中断", e)
        }
    }

    /**
     * 别的线程刚好触发了 watchdog 重置（关闭整条连接），正在飞的这一读就会拿到
     * 「DiskShare has already been closed」。这不是路径问题，重连一次就好 ——
     * 以前不重试，表现就是剧集页突然显示「这个文件夹里没有视频」。
     */
    private fun isClosedShare(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message.orEmpty()
            if (m.contains("already been closed") || m.contains("Bad file descriptor")) return true
            t = t.cause
        }
        return false
    }

    private fun <T> retryAfterReconnect(label: String, block: () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            if (!isClosedShare(e)) throw e
            Log.w(TAG, "$label 撞上已关闭的连接，重连后重试一次")
            closeQuietly()
            return block()
        }
    }

    @Synchronized
    fun ensureConnected(cfg: NasConfig): DiskShare = lock.withLock {
        val existing = share
        // 只判断 TCP 还活着不够：NAS 把空闲会话踢掉后 TCP 往往还挂着，
        // 这时拿旧 share 去 openFile 会一路失败，直到重启 App。会话/共享都得在。
        if (existing != null && current == cfg && connection?.isConnected == true &&
            session != null && share != null
        ) {
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

    /** 带上共享名容错：家长手写路径时常把共享名也写进去，见 stripShare */
    fun path(cfg: NasConfig, p: String): String = path(stripShare(cfg, p))

    /**
     * 清单里的路径约定是「相对视频共享根目录」，比如 儿童视频/01.学习类视频/xxx.mp4。
     * 但手写或别的工具生成时很容易把共享名也带上（儿童资源/儿童视频/...），
     * 或者粘成 smb://192.168.31.72/儿童资源/... —— 这两种都当成同一路径处理，
     * 免得一个前缀没写对，整张片单全黑。
     */
    fun stripShare(cfg: NasConfig, p: String): String {
        var s = p.trim()
        if (s.startsWith("smb://", ignoreCase = true)) {
            // smb://host/share/rest → share/rest（主机名丢掉，共享名在下面统一剥）
            s = s.substring(6).substringAfter('/', "")
        }
        s = s.replace('\\', '/').trim().trimStart('/')
        val sh = cfg.share.trim('/')
        if (sh.isNotEmpty() && s.startsWith("$sh/", ignoreCase = true)) {
            s = s.substring(sh.length + 1)
        }
        return s
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
    fun list(cfg: NasConfig, dir: String, shareName: String? = null): List<Triple<String, Boolean, Long>> =
        retryAfterReconnect("list($dir)") {
            withDeadline("list($dir)", 25_000) { listRaw(cfg, dir, shareName) }
        }

    private fun listRaw(cfg: NasConfig, dir: String, shareName: String?): List<Triple<String, Boolean, Long>> {
        val sh = if (shareName.isNullOrBlank() || shareName == cfg.share)
            ensureConnected(cfg) else shareNamed(cfg, shareName)
        val result = ArrayList<Triple<String, Boolean, Long>>()
        sh.list(path(cfg, dir)).forEach { f ->
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
            withDeadline("exists($file)", 15_000) { ensureConnected(cfg).fileExists(path(cfg, file)) }
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
    ): ByteArray = retryAfterReconnect("readAll($file)") {
        withDeadline("readAll($file)", 25_000) {
            readAllRaw(cfg, file, maxBytes, shareName)
        }
    }

    private fun readAllRaw(
        cfg: NasConfig,
        file: String,
        maxBytes: Int = 8 * 1024 * 1024,
        shareName: String? = null
    ): ByteArray {
        val sh = if (shareName.isNullOrBlank() || shareName == cfg.share)
            ensureConnected(cfg) else shareNamed(cfg, shareName)
        sh.openFile(
            path(cfg, file),
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
    fun open(cfg: NasConfig, file: String): SmbFile =
        withDeadline("open($file)", 20_000) { openRaw(cfg, file) }

    private fun openRaw(cfg: NasConfig, file: String): SmbFile {
        val sh = ensureConnected(cfg)
        return sh.openFile(
            path(cfg, file),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
    }

    /**
     * 带自愈地打开视频文件。
     *
     * 冷启动第一次播放最容易失败：NAS 把空闲的 TCP 会话断掉了、会话过期、
     * 或者握手还没完全就绪。这时 openFile 会抛异常；如果只有一次机会，
     * ExoPlayer 拿不到文件头，就会把它报成「格式不支持 / 容器解析失败」。
     * 再点一次往往就好了，因为连接已经被重新建过。
     *
     * 所以这里自己做掉：先原样重试一次（ensureConnected 会处理已断开的 TCP），
     * 仍失败才强制重建整条连接。每次 open 都带硬超时，卡死的读会被 watchdog 打断，
     * 不会再出现「点进去一直转圈、整机视频全废」的情况。
     */
    fun openRetrying(cfg: NasConfig, file: String, attempts: Int = 3): SmbFile {
        var last: Exception? = null
        for (i in 0 until attempts) {
            try {
                return open(cfg, file)
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "open ${i + 1}/$attempts 失败: $file / ${e.javaClass.simpleName}: ${e.message}")
                if (i == attempts - 1) break
                // 文件本身不存在就别重建连接（封面探测会大量命中这种情况）；
                // 其它错误说明会话可能已经半死，宁可立刻重握手，也别让孩子干等 20 秒超时。
                if (i >= 1 || (!isMissing(e) && !SmbPlaybackContext.playing)) closeQuietly()
                try {
                    Thread.sleep(250L * (i + 1))
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw IOException("打开失败(已重试 $attempts 次): $file", last)
    }

    /** 判断异常是不是「文件/目录不存在」，这类错误重建连接也没用 */
    private fun isMissing(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SMBApiException) {
                val s = t.status
                if (s == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
                    s == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND
                ) return true
            }
            t = t.cause
        }
        return false
    }

    fun fileSize(cfg: NasConfig, file: String): Long {
        return try {
            open(cfg, file).use { it.sizeBytes() }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 用一条独立的临时连接读小文件（清单 json 这类）。
     *
     * 定时轮询 tv.json 是唯一会「正好撞上」PC 正在写文件的时刻的操作：
     * 编辑器一保存，Samba 就可能让读端等 oplock 协商，而 PC 那边是脚本连接、
     * 根本不会回应，于是这一读就挂死。挂在主连接上就是全站瘫痪，
     * 所以这里独立握手、独立超时、用完就关，卡了也只影响自己。
     */
    fun readAllIsolated(
        cfg: NasConfig,
        shareName: String,
        file: String,
        timeoutMs: Long = 12_000
    ): ByteArray {
        val holder = AtomicReference<SMBClient?>(null)
        return withDeadline("isolated($file)", timeoutMs, onTimeout = {
            // 只掐这条临时连接，绝不动正在供货的主连接
            try {
                holder.get()?.close()
            } catch (e: Exception) {
            }
        }) {
            val conf = SmbConfig.builder()
                .withTimeout(timeoutMs / 2, TimeUnit.MILLISECONDS)
                .withSoTimeout(timeoutMs / 2, TimeUnit.MILLISECONDS)
                .withMultiProtocolNegotiate(true)
                .build()
            val c = SMBClient(conf)
            holder.set(c)
            val conn = c.connect(cfg.host, cfg.port)
            try {
                val ac = AuthenticationContext(cfg.user, cfg.password.toCharArray(), null)
                val sess = conn.authenticate(ac)
                val sh = sess.connectShare(shareName) as DiskShare
                sh.openFile(
                    path(cfg, file),
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
                        if (out.size() > 4 * 1024 * 1024) break
                    }
                    out.toByteArray()
                }
            } finally {
                try {
                    conn.close()
                } catch (e: Exception) {
                }
                try {
                    c.close()
                } catch (e: Exception) {
                }
            }
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
        // 故意不去抢 lock：卡死的读正握着它，等下去就是一起死。关掉 socket 就能让它抛出来。
        closeQuietly()
    }
}

/** smbj 没有直接的 fileLength()，从文件信息里取 */
fun SmbFile.sizeBytes(): Long = try {
    fileInformation.standardInformation.endOfFile
} catch (e: Exception) {
    -1L
}
