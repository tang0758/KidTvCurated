package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueMathTest {

    private val pos = mapOf(
        "p:/a/1.mp4" to 120_000L,   // 看了 2 分钟，没看完
        "p:/a/2.mp4" to 0L,         // 刚点开就退出
        "p:/a/3.mp4" to 590_000L,   // 已看到接近片尾
        "p:/a/4.mp4" to 60_000L,    // 没记时长，也应保留
        "p:/b/9.mp4" to 90_000L     // 片单里已经删掉的
    )
    private val dur = mapOf(
        "p:/a/1.mp4" to 600_000L,
        "p:/a/2.mp4" to 600_000L,
        "p:/a/3.mp4" to 600_000L,
        "p:/a/4.mp4" to 0L,
        "p:/b/9.mp4" to 600_000L
    )

    private fun eligible(keys: List<String>) =
        ContinueMath.eligible(keys, { pos[it] ?: 0L }, { dur[it] ?: 0L })

    @Test
    fun `只留没看完的 按最近顺序`() {
        assertEquals(
            listOf("p:/a/1.mp4", "p:/a/4.mp4", "p:/b/9.mp4"),
            eligible(listOf("p:/a/2.mp4", "p:/a/1.mp4", "p:/a/3.mp4", "p:/a/4.mp4", "p:/b/9.mp4"))
        )
    }

    @Test
    fun `最多 5 条`() {
        val all = (1..9).map { "p:/a/1.mp4" }
        assertEquals(5, ContinueMath.eligible(all, { 120_000L }, { 600_000L }).size)
    }

    @Test
    fun `片单里的条目沿用原 id 和标题 不重新编号`() {
        val known = mapOf("p:/a/1.mp4" to Item(id = "p:/a/1.mp4", title = "家长起的标题", file = "/a/1.mp4", cover = "x.jpg"))
        val item = ContinueMath.itemFor("p:/a/1.mp4", known, setOf("/a/1.mp4"), emptyList())
        assertEquals("家长起的标题", item?.title)
        assertEquals("x.jpg", item?.cover)
        assertEquals("p:/a/1.mp4", item?.key())
    }

    @Test
    fun `剧集页动态列出的单集也能还原 但必须在 folder 下`() {
        val item = ContinueMath.itemFor(
            "p:/cartoon/s1/05.mp4", emptyMap(), emptySet(), listOf("/cartoon")
        )
        assertEquals("/cartoon/s1/05.mp4", item?.file)
        assertEquals("p:/cartoon/s1/05.mp4", item?.key())
        assertTrue(item?.title?.isNotBlank() == true)
    }

    @Test
    fun `片单删掉的内容不再出现在继续看`() {
        assertNull(ContinueMath.itemFor("p:/b/9.mp4", emptyMap(), emptySet(), listOf("/cartoon")))
    }

    @Test
    fun `下标型旧 key 不认 避免整行错位`() {
        assertNull(ContinueMath.itemFor("daily_0", emptyMap(), emptySet(), listOf("/cartoon")))
        assertNull(ContinueMath.itemFor("p:../secret.mp4", emptyMap(), emptySet(), listOf("/cartoon")))
    }

    @Test
    fun `文件名收拾成短标题`() {
        assertEquals("Maisy 01", ContinueMath.titleOf("/cartoon/Maisy.01.1080p.mp4"))
        val messy = ContinueMath.titleOf("/a/【水印】S01E02.WEB-DL.1080p.mkv")
        assertTrue(messy.length <= 24)
        assertFalse(messy.contains("1080p"))
        assertTrue(ContinueMath.titleOf("/a/....mp4").isNotBlank())
    }

    @Test
    fun `分区 key 固定 不随条目顺序变`() {
        assertEquals("p:continue", ContinueMath.SECTION_KEY)
        assertEquals(5, ContinueMath.MAX_ITEMS)
    }
}
