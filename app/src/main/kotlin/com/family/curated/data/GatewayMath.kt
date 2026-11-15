package com.family.curated.data

/**
 * HTTP 媒体网关可用性缓存的状态机（纯逻辑，便于单测，见 R-38）。
 *
 * 2026-11-15 实机发现：探测原本在 `PlayerActivity.onCreate` 的主线程里直接发
 * `HttpURLConnection`，Android 抛 `NetworkOnMainThreadException` 被 `catch` 吞掉，
 * 结果恒为 false —— NAS 上 8765 明明在线，播放却永远走 SMB 兜底。
 * 现在网络探测只跑在后台线程，主线程只跟这份状态打交道。
 *
 * 这里刻意不 import `android.*` / `org.json`：纯 JVM 单测里它们是桩，碰一下就 `Stub!`。
 */
object GatewayMath {

    /** 探测结果的缓存有效期，与修复前保持一致 */
    const val PROBE_TTL_MS = 60_000L

    /** 主线程最多等后台探测多久；超时按「网关不可用」走 SMB，绝不卡住起播 */
    const val PROBE_WAIT_MS = 1_500L

    /** [available] 为 null 表示还没探测过；[lastProbeMs] 用墙上时间即可，时间回拨另做处理 */
    data class State(val available: Boolean? = null, val lastProbeMs: Long = 0L)

    /**
     * 缓存是否仍有效。从未探测过一律不算新鲜；系统时间回拨（age < 0）也判过期，
     * 避免一次回拨把缓存永久钉住。
     */
    fun fresh(state: State, nowMs: Long, ttlMs: Long = PROBE_TTL_MS): Boolean {
        if (state.available == null) return false
        val age = nowMs - state.lastProbeMs
        return age >= 0 && age < ttlMs
    }

    /** 是否该重新探测（缓存为空或已过期） */
    fun shouldProbe(state: State, nowMs: Long, ttlMs: Long = PROBE_TTL_MS): Boolean =
        !fresh(state, nowMs, ttlMs)

    /** 只有缓存新鲜且明确可用才走 HTTP；未知状态一律 SMB 兜底 */
    fun httpPreferred(state: State, nowMs: Long, ttlMs: Long = PROBE_TTL_MS): Boolean =
        fresh(state, nowMs, ttlMs) && state.available == true

    /** 后台探测结束后写入结果 */
    fun recorded(ok: Boolean, nowMs: Long): State = State(ok, nowMs)

    /** 播放出错时主动降级：TTL 内不再重试 HTTP，下一轮自然过期再探 */
    fun invalidated(nowMs: Long): State = State(false, nowMs)
}
