package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「NAS 上的清单这段时间有没有被改过」的判据回归。
 *
 * 这段判据错了两个方向都很难受：判成「没变」就会覆盖写、抹掉家长在网页上的改动；
 * 判成「变了」就让家长永远重放不了自己的修改。所以新旧两种指纹格式都得钉住。
 */
class PlaylistWriterHashTest {

    private val v1 = "{\"schedule\":{\"dailyLimitMinutes\":50}}".toByteArray(Charsets.UTF_8)
    private val v2 = "{\"schedule\":{\"dailyLimitMinutes\":60}}".toByteArray(Charsets.UTF_8)

    @Test
    fun `新格式指纹命中`() {
        assertTrue(PlaylistWriter.baseMatches(PlaylistWriter.contentHash(v1), v1))
    }

    @Test
    fun `旧版本存下的 32 位指纹仍然认`() {
        // 升级前 pendingBaseHash 里躺的是 hashCode 字符串，升级后那一次重放必须放行
        val legacy = v1.contentHashCode().toString()
        assertTrue(PlaylistWriter.baseMatches(legacy, v1))
    }

    @Test
    fun `内容变过就不放行`() {
        assertFalse(PlaylistWriter.baseMatches(PlaylistWriter.contentHash(v1), v2))
        assertFalse(PlaylistWriter.baseMatches(v1.contentHashCode().toString(), v2))
    }

    @Test
    fun `空指纹一律不放行`() {
        // 没存过指纹（老数据/异常路径）时宁可让家长重设一次，也不能盲目覆盖
        assertFalse(PlaylistWriter.baseMatches("", v1))
        assertFalse(PlaylistWriter.baseMatches("   ", v1))
    }

    @Test
    fun `32 位哈希撞上的两段内容不再被误判为同一份`() {
        // "Aa" 与 "BB" 的 hashCode 都是 2112；旧判据会把它们当成同一份清单
        val a = "Aa".toByteArray(Charsets.UTF_8)
        val b = "BB".toByteArray(Charsets.UTF_8)
        assertEquals(a.contentHashCode(), b.contentHashCode())
        assertTrue(PlaylistWriter.baseMatches(PlaylistWriter.contentHash(a), a))
        assertFalse(PlaylistWriter.baseMatches(PlaylistWriter.contentHash(a), b))
    }
}
