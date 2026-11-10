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
}
