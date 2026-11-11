package com.family.curated.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.share.DiskShare
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 异常落盘（OPT-17）。
 *
 * 为什么要有这个：`KidApp.tick` 里的 catch 以前是空的 —— 守卫「不能把应用搞崩」，
 * 但也就把线索一起咽了。电视上的问题，家长能给的描述只有「它自己变回去了」；
 * logcat 一断电就没，而 `adb logcat` 不是谁都会用。所以要落到家长手上也拿得到的地方：
 * NAS 的 `data:curated/logs/tv-yyyyMMdd.txt`。
 *
 * **先落本地，再传 NAS**，两段分开：
 *  1. `record()` 只往 `filesDir/diag` 里追加（毫秒级，主线程可承受），断网、NAS 挂了、
 *     甚至下一刻就崩溃，记录都在。这才是「证据」；
 *  2. `tick()` 由 1 秒心跳驱动，凑够 60 秒才在后台合并上传一次；失败退避 5 分钟。
 *     日志写不上去绝不影响孩子看电视 —— 这也是为什么不能直接在 catch 里读 NAS。
 *
 * 四条硬规矩：
 *  - **日志代码自己绝不抛异常**（[record] 里再套一层 try），否则查日志变成制造崩溃；
 *  - **同一处异常 60 秒只记一条**（[DiagMath.Deduper]），否则心跳一秒钟一条，200 KB 十分钟就满；
 *  - **播放中不传**（和 [StateBackup] 同一条理由），不跟正在供货的 SMB 争；
 *  - **只删自己起的文件名**（`tv-yyyyMMdd.txt`），`logs` 目录里家长放的东西一个都不动。
 */
object Diag {

    private const val TAG = "Diag"

    /** 本地最多攒这么多条，再多就丢最旧的：本地目录也不能无限长 */
    private const val MAX_LOCAL_ENTRIES = 300

    /** 两次上传之间的最短间隔 */
    private const val UPLOAD_GAP_MS = 60_000L

    /** 上传失败后的退避：别每 60 秒去敲一次已经不通的 NAS */
    private const val RETRY_GAP_MS = 5 * 60_000L

    private const val UPLOAD_TIMEOUT_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-io").apply { isDaemon = true }
    }

    private var appCtx: Context? = null
    private val deduper = DiagMath.Deduper(60_000L)

    /** 待上传的条数（内存里维护，避免每秒去数文件） */
    private val pendingCount = AtomicInteger(0)

    /** 最近几条，给设置页看 —— 不用为了看一眼就去连 NAS */
    private val recent = ArrayDeque<String>()

    @Volatile private var uploading = false
    @Volatile private var lastTryAt = 0L
    @Volatile private var nextTryAt = 0L
    @Volatile private var lastError = ""

    data class Result(val ok: Boolean, val message: String = "")

    // ---------------- 初始化 ----------------

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        try {
            val dir = dir()
            if (!dir.exists()) dir.mkdirs()
            // 上次没传上去的记录还算数：开机就把条数捞回来
            var n = 0
            localFiles().forEach { f ->
                n += DiagMath.countEntries(runCatching { f.readText() }.getOrDefault(""))
            }
            pendingCount.set(n)
            Log.i(TAG, "诊断日志就绪：${dir.absolutePath}，待上传 $n 条")
        } catch (e: Throwable) {
            Log.w(TAG, "诊断日志初始化失败（不影响使用）: ${e.javaClass.simpleName}")
        }
    }

    // ---------------- 记一条 ----------------

    /** 记录一次「本不该发生但也没到崩溃」的异常 */
    fun warn(tag: String, message: String, t: Throwable? = null) {
        record("W", tag, message, t)
    }

    /** 记录一次真正的失败（例如整段功能不可用） */
    fun error(tag: String, message: String, t: Throwable? = null) {
        record("E", tag, message, t)
    }

    /** 未捕获异常：只落本地，同步写完就把手交还给系统（此时不允许任何后台排队） */
    fun crash(t: Throwable) {
        try {
            val now = System.currentTimeMillis()
            val text = DiagMath.format(now, "E", "crash", "未捕获异常，应用即将退出", t, maxStack = 12)
            appendLocal(now, text)
            Log.e(TAG, "未捕获异常已落盘", t)
        } catch (e: Throwable) {
            // 这里再抛就真的是自杀了
        }
    }

    /**
     * 唯一入口。**加锁**：心跳在 UI 线程、开机广播在 receiver 线程、
     * 万一以后有后台线程也来记一笔，两个线程同时 append 会把一行字节拆开。
     */
    @Synchronized
    private fun record(level: String, tag: String, message: String, t: Throwable?) {
        try {
            val now = System.currentTimeMillis()
            val sig = DiagMath.signature(tag, t)
            // 同一个坑一分钟一条：心跳一秒钟抛一次，不去重能在十分钟内把 200 KB 填满
            if (!deduper.accept(sig, now)) return
            val text = DiagMath.format(now, level, tag, message, t) +
                DiagMath.repeatNote(deduper.takeSkipped())
            appendLocal(now, text)
            // logcat 也留一份：能连 adb 的时候不用去 NAS 翻
            if (level == "E") Log.e(TAG, "$tag: $message", t) else Log.w(TAG, "$tag: $message", t)
        } catch (e: Throwable) {
            // 日志写不进去也必须让主流程继续
        }
    }

    @Synchronized
    private fun appendLocal(ts: Long, text: String) {
        val ctx = appCtx ?: return
        val dir = File(ctx.filesDir, "diag")
        if (!dir.exists() && !dir.mkdirs()) return
        val f = File(dir, DiagMath.fileName(ClockGuard.day(ts)))
        FileOutputStream(f, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        pendingCount.incrementAndGet()
        synchronized(recent) {
            recent.addLast(text.trimEnd('\n'))
            while (recent.size > 12) recent.removeFirst()
        }
        // 单文件超 200 KB 就地滚动一次（读改写只在真的超了才发生）
        if (f.length() > DiagMath.MAX_BYTES) {
            val rolled = DiagMath.append("", f.readText(), DiagMath.MAX_BYTES)
            f.writeText(rolled)
        }
        if (pendingCount.get() > MAX_LOCAL_ENTRIES) trimLocal()
    }

    /** 本地攒太多时把最旧的文件整份丢掉：本地只是中转站，NAS 才是归档 */
    private fun trimLocal() {
        val files = localFiles()
        if (files.size <= 1) return
        files.dropLast(1).forEach { f ->
            runCatching { f.delete() }
        }
        pendingCount.set(
            localFiles().sumOf { DiagMath.countEntries(runCatching { it.readText() }.getOrDefault("")) }
        )
    }

    // ---------------- 上传 ----------------

    /** 由 KidApp 的 1 秒心跳调用；内部自己判断该不该传 */
    fun tick() {
        if (uploading) return
        if (pendingCount.get() <= 0) return
        val now = System.currentTimeMillis()
        if (now < nextTryAt) return
        if (now - lastTryAt < UPLOAD_GAP_MS) return
        // 播放中不碰 NAS：小文件也不差这一会儿（同 StateBackup 的取舍）
        if (SmbPlaybackContext.playing) return
        uploadAsync(null)
    }

    fun uploadAsync(onDone: ((Result) -> Unit)?) {
        if (uploading) return
        if (pendingCount.get() <= 0) {
            onDone?.let { main.post { it(Result(true, "没有待上传的日志")) } }
            return
        }
        uploading = true
        lastTryAt = System.currentTimeMillis()
        io.execute {
            val r = try {
                uploadNow()
            } catch (e: Throwable) {
                Result(false, "上传失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                uploading = false
            }
            if (!r.ok) {
                nextTryAt = System.currentTimeMillis() + RETRY_GAP_MS
                lastError = r.message
                Log.w(TAG, r.message)
            } else {
                nextTryAt = 0L
                lastError = ""
            }
            val cb = onDone ?: return@execute
            main.post { cb(r) }
        }
    }

    private fun uploadNow(): Result {
        val files = localFiles()
        if (files.isEmpty()) return Result(true, "没有待上传的日志")
        val cfg = Store.nas() ?: Store.DEFAULT_NAS
        val share = Store.playlistShare ?: Store.DEFAULT_PLAYLIST_SHARE
        val dir = logDirPath()

        // 一次最多传 3 个文件：断网几天可能攒了 7 份，一次做完要么顶到超时、
        // 要么让这条连接在 NAS 上吊太久。剩下的下一分钟再来。
        val batch = files.take(3)

        var uploaded = 0
        Smb.withIsolatedShare(cfg, share, "diag", UPLOAD_TIMEOUT_MS) { sh ->
            if (!sh.folderExists(Smb.path(dir))) {
                // 中间目录可能都不存在（家长把片单放进了一个新目录）
                runCatching { sh.mkdir(Smb.path(dir)) }
            }
            for (f in batch) {
                val text = runCatching { f.readText() }.getOrNull()
                if (text.isNullOrBlank()) {
                    runCatching { f.delete() }
                    continue
                }
                val remotePath = "$dir/${f.name}"
                val remote = readText(sh, remotePath)
                val merged = DiagMath.append(remote, text, DiagMath.MAX_BYTES)
                writeText(sh, remotePath, merged)
                uploaded += DiagMath.countEntries(text)
                // 写成功才删本地：宁可重复上传（合并时按内容追加），也不能丢
                runCatching { f.delete() }
            }
            prune(sh, dir)
        }

        Store.diagUploadedAt = System.currentTimeMillis()
        // 没传完的还算待上传，否则设置页会显示「0 条」而实际文件还在
        pendingCount.set(
            localFiles().sumOf { DiagMath.countEntries(runCatching { it.readText() }.getOrDefault("")) }
        )
        Log.i(TAG, "诊断日志已上传 $uploaded 条到 $dir，剩余 ${pendingCount.get()} 条")
        return Result(true, "已上传 $uploaded 条日志")
    }

    /** 清掉超过 7 天的文件；只删自己起的名字 */
    private fun prune(sh: DiskShare, dir: String) {
        val todayEpoch = ReportMath.parseDay(ClockGuard.day()) ?: return
        val list = runCatching { sh.list(Smb.path(dir)) }.getOrNull() ?: return
        for (info in list) {
            val name = info.fileName
            if (name == "." || name == "..") continue
            val isDir = EnumWithValue.EnumUtils.isSet(
                info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY
            )
            if (isDir) continue
            if (!DiagMath.expired(name, todayEpoch)) continue
            runCatching { sh.rm(Smb.path("$dir/$name")) }
                .onSuccess { Log.i(TAG, "清理过期日志 $name") }
        }
    }

    private fun readText(sh: DiskShare, path: String): String {
        return try {
            sh.openFile(
                Smb.path(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { f ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var off = 0L
                while (true) {
                    val n = f.read(buf, off, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    off += n
                    if (out.size() > DiagMath.MAX_BYTES * 2) break
                }
                out.toString("UTF-8")
            }
        } catch (e: Exception) {
            // 第一次写日志时文件本来就不存在，这是正常情况；
            // 但「读失败」和「没有这个文件」必须分开 —— 网络抖一下就当成空文件覆盖掉，
            // 会把 NAS 上攒了几天的日志整份抹了，那比不记还糟。
            if (isMissing(e)) "" else throw e
        }
    }

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

    private fun writeText(sh: DiskShare, path: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sh.openFile(
            Smb.path(path),
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        ).use { f ->
            var pos = 0
            while (pos < bytes.size) {
                val n = minOf(64 * 1024, bytes.size - pos)
                f.write(bytes, pos.toLong(), pos, n)
                pos += n
            }
        }
    }

    // ---------------- 给设置页 ----------------

    fun pending(): Int = pendingCount.get()

    fun lastUploadAt(): Long = Store.diagUploadedAt

    fun lastError(): String = lastError

    fun logDirPath(): String {
        val base = Store.playlistPath.substringBeforeLast('/', "")
        return if (base.isEmpty()) "logs" else "$base/logs"
    }

    /** 「待上传 3 条 · 12:31 已上传」这样的状态文案 */
    fun statusText(): String {
        val n = pending()
        val at = lastUploadAt()
        val whenText = if (at <= 0L) "还没上传过" else {
            val min = (System.currentTimeMillis() - at) / 60_000L
            when {
                min < 1 -> "刚刚上传"
                min < 60 -> "$min 分钟前上传"
                min < 60 * 24 -> "${min / 60} 小时前上传"
                else -> "${min / (60 * 24)} 天前上传"
            }
        }
        val err = lastError()
        return when {
            n > 0 && err.isNotEmpty() -> "待上传 $n 条 · $err"
            n > 0 -> "待上传 $n 条 · $whenText"
            else -> "暂无异常 · $whenText"
        }
    }

    /** 最近几条的文本（新的在前），给对话框直接显示 */
    fun recentText(limit: Int = 3, maxChars: Int = 1200): String {
        val items = synchronized(recent) { recent.toList() }
        if (items.isEmpty()) return "本地还没有记录。"
        val picked = items.takeLast(limit).reversed()
        val sb = StringBuilder()
        for (item in picked) {
            if (sb.length + item.length > maxChars) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(item)
        }
        return if (sb.isEmpty()) picked.first().take(maxChars) else sb.toString()
    }

    /** 清空：本地待上传的 + 内存里的最近几条 */
    fun clear() {
        try {
            localFiles().forEach { runCatching { it.delete() } }
            pendingCount.set(0)
            synchronized(recent) { recent.clear() }
            Log.i(TAG, "诊断日志已清空（NAS 上已归档的不动）")
        } catch (e: Throwable) {
        }
    }

    // ---------------- 文件 ----------------

    private fun dir(): File {
        val ctx = appCtx ?: return File(".")
        return File(ctx.filesDir, "diag")
    }

    private fun localFiles(): List<File> {
        val d = try {
            dir()
        } catch (e: Throwable) {
            return emptyList()
        }
        return d.listFiles { f -> f.isFile && f.name.startsWith("tv-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.name } ?: emptyList()
    }
}
