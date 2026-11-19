package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAckTest {

    @Test
    fun `textSha256 ignores leading UTF8 BOM`() {
        val plain = """{"sections":[]}"""
        val withBom = "\uFEFF$plain"
        assertEquals(TvAck.textSha256(plain), TvAck.textSha256(withBom))
        assertEquals(64, TvAck.textSha256(plain).length)
    }

    @Test
    fun `ackKey changes when either tvText or learningText changes`() {
        val k1 = TvAck.ackKey("""{"a":1}""", """{"cards":[]}""")
        val k2 = TvAck.ackKey("""{"a":2}""", """{"cards":[]}""")
        val k3 = TvAck.ackKey("""{"a":1}""", """{"cards":[{"id":"1"}]}""")
        val k4 = TvAck.ackKey("""{"a":1}""", null)
        assertNotEquals(k1, k2)
        assertNotEquals(k1, k3)
        assertNotEquals(k1, k4)
        assertEquals(k1, TvAck.ackKey("\uFEFF{\"a\":1}", "\uFEFF{\"cards\":[]}"))
    }

    @Test
    fun `ackPath stays in same folder as tv json`() {
        assertEquals("curated/tv.ack.json", TvAck.ackPath("curated/tv.json"))
        assertEquals("tv.ack.json", TvAck.ackPath("tv.json"))
    }

    @Test
    fun `formatAckJson produces expected keys`() {
        val json = TvAck.formatAckJson(
            tvHash = "abc",
            learningHash = "def",
            appliedAt = "2026-11-19T10:45:00+08:00",
            versionCode = 18,
            versionName = "2.1",
            sectionsCount = 5,
            configVersion = "2026-11-19 22:15:00"
        )
        assertTrue(json.contains("\"tvHash\": \"abc\""))
        assertTrue(json.contains("\"learningHash\": \"def\""))
        assertTrue(json.contains("\"configVersion\": \"2026-11-19 22:15:00\""))
        assertTrue(json.contains("\"versionCode\": 18"))
        assertTrue(json.contains("\"sectionsCount\": 5"))
    }
}
