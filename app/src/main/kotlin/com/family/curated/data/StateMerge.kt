package com.family.curated.data

/**
 * 记录备份的纯逻辑（OPT-09）：哪些键值得备份、两边冲突怎么合、什么算合法数据。
 *
 * 这一层不许碰 `android.*` 或 `org.json` —— 纯 JVM 单测里它们是桩类，一调用就
 * `RuntimeException: Stub!`（Wave 2 的 AutoNext 踩过一次）。JSON 的读写留在
 * [StateBackup] 里，这里只处理内存里的 Map / Set。
 *
 * 核心策略是**只升不降**：同一个键两边都有值时取大的，打卡记录取并集。
 * 于是导入天然幂等，重复导、导到一半断电再导，都不会把孩子的记录弄小。
 * 反过来说，这套东西**不能**用来「回退」数据 —— 家长要清空进度就用设置页的
 * 「清空播放进度」，那条路径会立刻把新的空状态备份回 NAS。
 */
object StateMerge {

    /** 备份格式版本。读到更大的版本号就整份忽略，免得旧 App 乱解释新字段 */
    const val VERSION = 1

    /** 备份这些前缀下的长整数键（时长、进度、防跳看、最近点开） */
    val LONG_PREFIXES = listOf("watch_", "ext_", "vids_", "pos_", "dur_", "front_", "played_")

    /**
     * 其中这一组是「用量」，重装后必须**自动**补回来：
     * 本地归零就等于白送了孩子一段额度，这正是 EX-13 那条绕过路径。
     * 补回来只会让今天的已用时长变大（更严格），不存在被滥用的方向。
     */
    val USAGE_PREFIXES = listOf("watch_", "ext_", "vids_")

    /** 进度/打卡这类「记录」，导入前要问家长：他可能刚故意清空过 */
    private val RECORD_PREFIXES = listOf("pos_", "dur_", "front_", "played_")

    /** 单条键名上限，防止备份文件被写坏后塞进超长垃圾 */
    private const val MAX_KEY_LEN = 200

    /** 最多保留多少天打卡（约 13 个月，够看完一整年的成长） */
    const val MAX_STAMPS = 400

    /** 最多保留多少个长整数键 */
    const val MAX_LONGS = 5000

    data class Snapshot(
        val stamps: Set<String>,
        val longs: Map<String, Long>,
        val savedAt: Long = 0L
    ) {
        val isEmpty: Boolean get() = stamps.isEmpty() && longs.isEmpty()
    }

    fun isLongKey(k: String): Boolean = LONG_PREFIXES.any { k.startsWith(it) }

    fun isUsageKey(k: String): Boolean = USAGE_PREFIXES.any { k.startsWith(it) }

    fun isRecordKey(k: String): Boolean =
        RECORD_PREFIXES.any { k.startsWith(it) } || k == "stamps"

    /** 日期键只认 yyyy-MM-dd，且年月日范围要说得通 —— 备份文件是明文，可能被手改 */
    fun isDayKey(s: String): Boolean {
        if (s.length != 10 || s[4] != '-' || s[7] != '-') return false
        val y = s.substring(0, 4).toIntOrNull() ?: return false
        val m = s.substring(5, 7).toIntOrNull() ?: return false
        val d = s.substring(8, 10).toIntOrNull() ?: return false
        return y in 2000..2100 && m in 1..12 && d in 1..31
    }

    /** 过滤非法日期、去重，只留最近 [MAX_STAMPS] 天（按日期字符串排序即可，格式是定长的） */
    fun sanitizeStamps(raw: Collection<String>): Set<String> {
        val days = raw.asSequence()
            .map { it.trim() }
            .filter { isDayKey(it) }
            .distinct()
            .sorted()
            .toList()
        val keep = if (days.size > MAX_STAMPS) days.subList(days.size - MAX_STAMPS, days.size) else days
        return LinkedHashSet(keep)
    }

    /** 过滤掉非白名单前缀、负数、超长键；宁可少恢复几条，也别让坏文件污染本地 */
    fun sanitizeLongs(raw: Map<String, Long>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((k, v) in raw) {
            if (k.length > MAX_KEY_LEN) continue
            if (!isLongKey(k)) continue
            if (v < 0L) continue
            out[k] = v
            if (out.size >= MAX_LONGS) break
        }
        return out
    }

    /** 只升不降：同键取大值，打卡取并集 */
    fun merge(local: Snapshot, remote: Snapshot): Snapshot {
        val stamps = LinkedHashSet(local.stamps)
        stamps.addAll(remote.stamps)
        val longs = LinkedHashMap(local.longs)
        remote.longs.forEach { (k, v) ->
            val cur = longs[k]
            if (cur == null || v > cur) longs[k] = v
        }
        return Snapshot(stamps, longs, maxOf(local.savedAt, remote.savedAt))
    }

    /** 合并后真正「变多」的条目数；0 表示没什么可恢复的，不用打扰家长 */
    fun raised(local: Snapshot, merged: Snapshot): Int {
        var n = merged.stamps.size - local.stamps.size
        merged.longs.forEach { (k, v) -> if ((local.longs[k] ?: 0L) < v) n++ }
        return n.coerceAtLeast(0)
    }

    /** 只保留用量部分（冷启动静默补回时用） */
    fun onlyUsage(s: Snapshot): Snapshot =
        Snapshot(emptySet(), s.longs.filterKeys { isUsageKey(it) }, s.savedAt)

    /** 只保留记录部分（进度 + 打卡） */
    fun onlyRecords(s: Snapshot): Snapshot =
        Snapshot(s.stamps, s.longs.filterKeys { isRecordKey(it) }, s.savedAt)

    /** 给家长看的一句话摘要 */
    fun summary(s: Snapshot): String {
        val days = s.stamps.size
        val vids = s.longs.count { it.key.startsWith("pos_") && it.value > 0L }
        val parts = ArrayList<String>(2)
        if (days > 0) parts.add("$days 天打卡")
        if (vids > 0) parts.add("$vids 条播放进度")
        return if (parts.isEmpty()) "空记录" else parts.joinToString(" · ")
    }

    /**
     * 冷启动要不要问家长恢复：本地一条打卡都没有（重装 / 恢复出厂 / `pm clear`），
     * 而 NAS 上确实有东西。本地本来就有记录时不必问 —— 那只会变成烦人的弹窗。
     */
    fun shouldPrompt(localStampCount: Int, remote: Snapshot?): Boolean =
        localStampCount == 0 && remote != null && !remote.isEmpty
}
