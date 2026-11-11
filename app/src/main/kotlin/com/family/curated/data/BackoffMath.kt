package com.family.curated.data

/**
 * 备份失败后的重试退避（纯逻辑，便于单测）。
 *
 * 为什么单独抽出来：`StateBackup.tick()` 原来只按「上次**成功**时间」判断该不该做，
 * 失败时那个时间不动，于是 5 分钟阈值一直成立 —— NAS 一断线就退化成**每秒**重连一次：
 * 每秒打一次 NAS、每秒一行日志，还把 `PlaylistSync` 的单线程队列占满，
 * 连带片单同步和「未同步的修改」重试都排队等它。
 *
 * 这里只回答一个问题：连续失败 [streak] 次之后，隔多久再试。
 * 失败越多退得越远，到 [MAX_MS] 封顶 —— 封顶而不是无限增长，
 * 是为了 NAS 半夜恢复供电后，第二天早上还能自己接上。
 */
object BackoffMath {

    /** 第一次失败后等 5 分钟再试（和 `StateBackup.INTERVAL_MS` 对齐，不改变正常节奏） */
    const val BASE_MS = 5 * 60_000L

    /** 退避上限；30 分钟一次尝试，对 NAS 和电视都是可忽略的负担 */
    const val MAX_MS = 30 * 60_000L

    /**
     * [streak] 是连续失败次数；0（上次成功）表示不需要退避，返回 0。
     * 乘法前先转 Long，`Int.MAX_VALUE` 也不会溢出成负数。
     */
    fun retryDelayMs(streak: Int): Long {
        if (streak <= 0) return 0L
        val grown = BASE_MS * streak.toLong()
        return if (grown > MAX_MS) MAX_MS else grown
    }
}
