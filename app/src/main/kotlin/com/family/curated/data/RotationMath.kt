package com.family.curated.data

/**
 * 每日自动轮换（auto）纯计算逻辑：按「实际开机使用日」推进，关机没看的天数不跳过。
 * 零 Android / org.json 依赖，便于纯 JVM 单测。
 */
object RotationMath {

    data class State(
        val dayIndex: Int,
        val lastDay: String,
        val folder: String
    )

    /**
     * 计算今天应使用的轮换天序号：
     * - 首次启用或切换了新目录：从第 0 天（第 1 集）开始；
     * - 同一天重复打开/同步：保持原索引不重复推进；
     * - 跨天（无论中间关机 1 天还是 7 天）：仅 +1，确保不跳过未看的集数；
     * - 系统日期倒退（today < lastDay）：保持原索引不回退。
     */
    fun advance(prev: State?, folder: String, today: String): State {
        val cleanFolder = folder.trim().trim('/')
        if (prev == null || prev.folder != cleanFolder || prev.lastDay.isBlank()) {
            return State(dayIndex = 0, lastDay = today, folder = cleanFolder)
        }
        if (today <= prev.lastDay) {
            return prev
        }
        return State(dayIndex = prev.dayIndex + 1, lastDay = today, folder = cleanFolder)
    }
}
