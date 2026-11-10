package com.family.curated.data

/**
 * 外部应用结算的纯计算，抽出来给 JVM 单测用（不碰 Android 框架）。
 * ADR-01 第 6.3 节：两个读数都要留，偏差超过 20% 时以系统累计值为准。
 */
object ExtClockMath {

    /**
     * @param estMs 会话开始到现在的 elapsedRealtime 差值，秒级驱动用
     * @param sysMs UsageStats 的 totalTimeInForeground 增量，退出后纠偏用；拿不到传 0
     */
    fun calibrate(estMs: Long, sysMs: Long): Long {
        if (estMs < 1_000L) return 0L               // 极短会话按 0，避免除零和「点一下就扣一分钟」
        if (sysMs <= 0L) return estMs               // ROM 不给系统值 → 用估算
        val diff = kotlin.math.abs(sysMs - estMs)
        // 整数比较，等价于 diff / est > 0.20
        return if (diff * 100L / estMs > 20L) sysMs else estMs
    }
}
