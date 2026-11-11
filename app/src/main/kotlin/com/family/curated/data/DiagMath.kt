package com.family.curated.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志的纯逻辑（OPT-17）：文件名、过期判定、200 KB 滚动、格式化、去重。
 *
 * 为什么单独拆一层：日志本身出错是最难查的问题（你没地方再记一笔），
 * 所以「滚动丢掉哪几行」「哪个文件算过期」这类判断必须能在电脑上单测，
 * 而不是等电视上写坏了才发现。这一层不碰 `android.*`，也不碰 NAS。
 */
object DiagMath {

    /** 单个日志文件上限（ROADMAP OPT-17 定的 200 KB） */
    const val MAX_BYTES = 200 * 1024

    /** 保留天数：今天 + 前 6 天 */
    const val KEEP_DAYS = 7

    /** 单行上限：一个超长路径不该把整个文件撑爆 */
    const val MAX_LINE = 400

    /** 默认栈帧条数：够定位，又不至于一条异常占半屏 */
    const val MAX_STACK = 6

    /** 滚动后插在最前面的提示，避免家长以为日志丢了一半 */
    const val ROLL_MARKER = "# 更早的日志已滚动丢弃（单文件上限 200 KB）"

    private val FILE_RE = Regex("^tv-(\\d{8})\\.txt$")

    // ---------------- 文件名 ----------------

    /**
     * `2026-11-11` → `tv-20261111.txt`。也接受已经是 `20261111` 的形式。
     * 解析不出来时退化成 `tv-unknown.txt`（宁可名字难看，也不要抛异常）。
     */
    fun fileName(day: String): String {
        val digits = day.filter { it.isDigit() }
        return if (digits.length == 8) "tv-$digits.txt" else "tv-unknown.txt"
    }

    /** 文件名 → 那天的 epoch day；不是本 App 的日志文件返回 null */
    fun epochOfFile(name: String): Long? {
        val m = FILE_RE.matchEntire(name) ?: return null
        val d = m.groupValues[1]
        val y = d.substring(0, 4).toIntOrNull() ?: return null
        val mo = d.substring(4, 6).toIntOrNull() ?: return null
        val da = d.substring(6, 8).toIntOrNull() ?: return null
        if (y !in 2000..2100 || mo !in 1..12 || da !in 1..31) return null
        return ReportMath.toEpochDay(y, mo, da)
    }

    /**
     * 该不该删。
     *
     * 只认 `tv-yyyyMMdd.txt`；别的文件名一律不删 —— 那是家长自己放进 logs 目录的东西，
     * 我们没有资格替他做主。日期解析不出来（比如 `tv-unknown.txt`）也不删。
     */
    fun expired(name: String, todayEpoch: Long, keepDays: Int = KEEP_DAYS): Boolean {
        val e = epochOfFile(name) ?: return false
        val keep = keepDays.coerceAtLeast(1)
        return e < todayEpoch - (keep - 1)
    }

    // ---------------- 格式化 ----------------

    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun stamp(ts: Long): String = synchronized(stampFmt) { stampFmt.format(Date(ts)) }

    /**
     * 一条异常的完整文本块（结尾带换行）。
     *
     * 形如：
     * ```
     * 2026-11-11 16:54:02 W/tick 守卫心跳异常
     *   java.lang.IllegalStateException: boom
     *     at com.family.curated.KidApp$tick$1.run(KidApp.kt:73)
     * ```
     */
    fun format(
        ts: Long,
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        maxStack: Int = MAX_STACK
    ): String {
        val sb = StringBuilder()
        sb.append(stamp(ts)).append(' ').append(level).append('/').append(tag)
        if (message.isNotBlank()) sb.append(' ').append(oneLine(message))
        sb.append('\n')
        if (throwable != null) {
            sb.append("  ").append(oneLine(describe(throwable))).append('\n')
            val frames = throwable.stackTrace
            var shown = 0
            for (f in frames) {
                if (shown >= maxStack) break
                sb.append("    at ").append(oneLine(f.toString())).append('\n')
                shown++
            }
            if (frames.size > shown) {
                sb.append("    ... 另有 ").append(frames.size - shown).append(" 帧\n")
            }
        }
        return sb.toString()
    }

    /** `java.lang.IllegalStateException: boom`；message 为空时只写类名 */
    fun describe(t: Throwable): String {
        val name = t.javaClass.name
        val m = t.message?.trim()
        return if (m.isNullOrEmpty()) name else "$name: $m"
    }

    /** 去换行 + 截断：一条日志绝不能把文件顶出去 */
    fun oneLine(s: String, max: Int = MAX_LINE): String {
        val flat = s.replace("\r", " ").replace("\n", "\\n").trim()
        return if (flat.length <= max) flat else flat.substring(0, max) + "…"
    }

    /**
     * 去重指纹：同一处代码反复抛同一个异常，一小时能刷出 3600 条一模一样的记录，
     * 200 KB 立刻被填满，真正的第一条反而被滚动丢掉。
     * 取「标签 + 异常类 + 第一个栈帧」，同一个坑算一条。
     */
    fun signature(tag: String, t: Throwable?): String {
        if (t == null) return tag
        val top = t.stackTrace.firstOrNull()
        val where = if (top == null) "" else "${top.className}.${top.methodName}:${top.lineNumber}"
        return "$tag|${t.javaClass.name}|$where"
    }

    /** 被合并掉的重复次数，跟在被保留的那条后面 */
    fun repeatNote(skipped: Int): String =
        if (skipped <= 0) "" else "  （同类异常在此之前又出现了 $skipped 次，已合并）\n"

    // ---------------- 200 KB 滚动 ----------------

    /**
     * 把 [add] 追加到 [existing] 后面，超过 [maxBytes] 时**从最旧的行开始整行丢弃**。
     *
     * 必须整行丢：截半个字符会留下一条读不懂的残句，还不如干净地从某一条开始。
     * 丢过内容时在开头插 [ROLL_MARKER]，让人知道这里断过。
     */
    fun append(existing: String, add: String, maxBytes: Int = MAX_BYTES): String {
        if (add.isBlank()) return existing
        val all = ArrayList<String>()
        if (existing.isNotBlank()) all.addAll(existing.trimEnd('\n').split('\n'))
        all.addAll(add.trimEnd('\n').split('\n'))
        val joined = all.joinToString("\n") + "\n"
        // 64 字节的下限只是防止有人传 0/负数把文件清成空；不要更高，
        // 否则调用方要求的上限会被悄悄放大（单测里就撞过这个）
        val limit = maxBytes.coerceAtLeast(64)
        if (utf8Len(joined) <= limit) return joined

        val markerLine = ROLL_MARKER + "\n"
        val budget = limit - utf8Len(markerLine)
        // 从最新的一行往前累加，装满为止：保证最新的日志一定留得住
        var used = 0
        var start = all.size
        while (start > 0) {
            val lineBytes = utf8Len(all[start - 1]) + 1
            if (used + lineBytes > budget) break
            used += lineBytes
            start--
        }
        val kept = all.subList(start, all.size).joinToString("\n") + "\n"
        return markerLine + kept
    }

    private fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /**
     * 丢掉第一行（多半是不完整的那行）。
     *
     * 远端日志超过读取上限时只读**尾部**，起点落在任意字节上：第一行必然是半行，
     * 还可能把一个 UTF-8 字符劈成两半变成 `�`。留着就是一条读不懂的残句。
     * 没有换行（整段都不完整）时返回空串，宁可少一条也别写残句。
     */
    fun trimLeadingPartial(text: String): String {
        val i = text.indexOf('\n')
        return if (i >= 0) text.substring(i + 1) else ""
    }

    /** 一个文件里有多少条记录（每条的第一行以「日期 时间」开头） */
    private val ENTRY_RE = Regex("(?m)^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} ")

    fun countEntries(text: String): Int = ENTRY_RE.findAll(text).count()

    // ---------------- 去重器 ----------------

    /**
     * 同一个指纹在 [quietMs] 内只放行一条，其余计数合并。
     * 用一个可变对象而不是全局状态，单测里好构造。
     */
    class Deduper(private val quietMs: Long = 60_000L) {
        private var lastSig = ""
        private var lastAt = 0L
        private var skipped = 0

        /** true = 这条要写；false = 被抑制，已计入 [takeSkipped] */
        fun accept(sig: String, now: Long): Boolean {
            if (sig == lastSig && now - lastAt < quietMs) {
                skipped++
                return false
            }
            lastSig = sig
            lastAt = now
            return true
        }

        /** 取走累计的重复次数并清零 */
        fun takeSkipped(): Int {
            val n = skipped
            skipped = 0
            return n
        }
    }
}
