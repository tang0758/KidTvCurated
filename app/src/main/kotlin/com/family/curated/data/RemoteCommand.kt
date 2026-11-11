package com.family.curated.data

import java.security.MessageDigest

/**
 * 远程控制卡 control.json 的纯逻辑部分（OPT-06）。
 * 不依赖 Android / org.json，方便单测；JSON 解析留在 [RemoteControl]。
 *
 * 设计约束：
 *  - 写坏的、缺字段的文件一律退化成「什么都不做」，绝不影响片单同步（EX-14）；
 *  - 幂等按 `updatedAt` 去重；家长忘了写 updatedAt 时退化为「内容指纹」，
 *    改一个字段仍会执行一次，不会出现「怎么点都没反应」。
 */
object RemoteCommand {

    /** 单次加时上限，和电视设置页的临时放行档位保持一致 */
    const val MAX_MINUTES = 240

    fun clampMinutes(v: Int): Int = v.coerceIn(0, MAX_MINUTES)

    /** 计算字节数组的 SHA-256 十六进制指纹，避免 32 位 hashCode 碰撞 */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(Character.forDigit(v ushr 4, 16))
            sb.append(Character.forDigit(v and 0x0F, 16))
        }
        return sb.toString()
    }

    fun idempotencyKey(updatedAt: String, rawText: String): String =
        updatedAt.trim().ifBlank { sha256Hex(rawText.trim().toByteArray(Charsets.UTF_8)) }

    /**
     * 这一纸指令会执行哪些动作（写回执、排障日志都用这份）。
     * `lockNow` 显式写成 false 也算一个动作 —— 那是家长在解除远程锁定。
     */
    fun actions(
        lockNow: Boolean,
        hasLockField: Boolean,
        addMinutes: Int,
        pauseNow: Boolean
    ): List<String> {
        val out = ArrayList<String>(3)
        if (lockNow) out.add("lockNow")
        else if (hasLockField) out.add("unlock")
        if (addMinutes > 0) out.add("addMinutes:$addMinutes")
        if (pauseNow) out.add("pauseNow")
        return out
    }
}
