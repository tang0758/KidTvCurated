package com.family.curated.data

/**
 * 时钟校正的纯计算部分：不碰任何 Android API，可以直接单测。
 *
 * 为什么需要它：限额和打卡都挂在「今天是哪天」上，
 * 而 `System.currentTimeMillis()` 是家长/孩子改一次电视日期就能拨动的。
 * 判定逻辑本身很短，但错一次就是「孩子被冤枉」或「规则被绕开」，
 * 所以把数学部分单独拎出来，用「最近一次可信时间 + 真实流逝的单调时间」来对照。
 */
object ClockMath {

    /** 允许的偏差。5 分钟内算正常（含 NTP 小步校正、夏令时级别的小抖动） */
    const val TOLERANCE_MS = 5 * 60_000L

    data class State(
        /** 最近一次「墙上时间可信」的时刻（墙上时间） */
        val goodWall: Long = 0L,
        /** 同一时刻的单调时间（elapsedRealtime） */
        val goodRt: Long = 0L,
        /** 当前是否处于「不信任墙上时间」状态 */
        val frozen: Boolean = false
    )

    data class Reading(
        /** 校正后、保证单调的「现在」 */
        val guarded: Long,
        val state: State,
        /** 本次是不是一次新的异常（只在刚进入冻结时为 true，避免每次调用都记一笔） */
        val anomaly: Boolean,
        /** 本次是不是刚从异常里恢复（时间被改回来了） */
        val recovered: Boolean
    )

    /**
     * @param wall  System.currentTimeMillis()
     * @param rt    SystemClock.elapsedRealtime()
     */
    fun evaluate(wall: Long, rt: Long, s: State): Reading {
        // 首次运行 / 刚开机：没有可信基线，直接采信当前时间
        if (s.goodWall <= 0L || s.goodRt <= 0L) {
            return Reading(wall, State(wall, rt, false), false, false)
        }
        val expected = s.goodWall + (rt - s.goodRt)
        val drift = wall - expected
        if (drift > -TOLERANCE_MS && drift < TOLERANCE_MS) {
            // 正常，或被家长改回了正确时间 —— 重新建立基线
            return Reading(wall, State(wall, rt, false), false, s.frozen)
        }
        // 时间不可信（后退或大跨度前跳）：冻结在「上次可信时间 + 真实流逝」，
        // 日期键因此不会跳到明天，限额也不会被清零
        return Reading(expected, State(s.goodWall, s.goodRt, true), !s.frozen, false)
    }
}
