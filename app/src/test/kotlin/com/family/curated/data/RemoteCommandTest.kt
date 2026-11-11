package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPT-06 控制卡的纯逻辑回归：幂等与「加时不能变成无限放行」。 */
class RemoteCommandTest {

    @Test
    fun `加时上限夹紧到 0 到 240 分钟`() {
        assertEquals(0, RemoteCommand.clampMinutes(-5))
        assertEquals(0, RemoteCommand.clampMinutes(0))
        assertEquals(15, RemoteCommand.clampMinutes(15))
        assertEquals(240, RemoteCommand.clampMinutes(240))
        assertEquals(240, RemoteCommand.clampMinutes(9999))
    }

    @Test
    fun `有 updatedAt 时按字符串去重`() {
        assertEquals("2026-11-10T22:15:00+08:00",
            RemoteCommand.idempotencyKey("2026-11-10T22:15:00+08:00", "{}"))
        assertEquals("x", RemoteCommand.idempotencyKey("  x  ", "anything"))
    }

    @Test
    fun `没写 updatedAt 时退化为内容指纹`() {
        val a = RemoteCommand.idempotencyKey("", "{\"lockNow\":true}")
        val b = RemoteCommand.idempotencyKey("", "{\"lockNow\":true}")
        val c = RemoteCommand.idempotencyKey("", "{\"addMinutes\":10}")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `空控制卡不产生任何动作`() {
        assertTrue(RemoteCommand.actions(false, false, 0, false).isEmpty())
    }

    @Test
    fun `立即锁屏与解除是两种动作`() {
        assertEquals(listOf("lockNow"), RemoteCommand.actions(true, true, 0, false))
        assertEquals(listOf("unlock"), RemoteCommand.actions(false, true, 0, false))
    }

    @Test
    fun `一条卡可以同时带加时和暂停`() {
        assertEquals(
            listOf("lockNow", "addMinutes:10", "pauseNow"),
            RemoteCommand.actions(true, true, 10, true)
        )
    }

    @Test
    fun `SHA-256 与标准向量一致`() {
        // NIST 官方向量：sha256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RemoteCommand.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            RemoteCommand.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun `内容指纹不受 32 位哈希碰撞影响`() {
        // "Aa" 与 "BB" 的 String.hashCode() 都是 2112，旧的 hashCode 指纹在这里必然相撞。
        // 这个指纹决定「能不能覆盖 NAS 上的 tv.json」，撞一次就是抹掉家长的改动。
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(
            RemoteCommand.sha256Hex("Aa".toByteArray(Charsets.UTF_8)),
            RemoteCommand.sha256Hex("BB".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `没写 updatedAt 时的指纹就是内容 SHA-256`() {
        val raw = "{\"lockNow\":true}"
        assertEquals(
            RemoteCommand.sha256Hex(raw.toByteArray(Charsets.UTF_8)),
            RemoteCommand.idempotencyKey("", raw)
        )
        // 首尾空白不该改变身份，否则同一张卡会被当成两张
        assertEquals(
            RemoteCommand.idempotencyKey("", raw),
            RemoteCommand.idempotencyKey("", "  $raw\n")
        )
    }
}
