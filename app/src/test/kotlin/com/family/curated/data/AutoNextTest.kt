package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoNextTest {

    private val items = listOf(
        Item(id = "p:/cartoon/s1/01.mp4", title = "第 1 集", file = "/cartoon/s1/01.mp4"),
        Item(id = "p:/cartoon/s1/02.mp4", title = "第 2 集", file = "/cartoon/s1/02.mp4"),
        Item(id = "p:/cartoon/s1/03.mp4", title = "第 3 集", file = "/cartoon/s1/03.mp4")
    )

    @Test
    fun `队列往返保住路径派生的 key`() {
        val q = AutoNext.decode(AutoNext.encode(items))
        assertEquals(3, q.size)
        assertEquals("/cartoon/s1/02.mp4", q[1].file)
        assertEquals("p:/cartoon/s1/02.mp4", q[1].key)
        assertEquals("第 2 集", q[1].title)
    }

    @Test
    fun `folder 条目不进队列`() {
        val mixed = items + Item(id = "p:/cartoon/s2", title = "第二季", folder = "/cartoon/s2")
        assertEquals(3, AutoNext.decode(AutoNext.encode(mixed)).size)
    }

    @Test
    fun `next 取下一集 最后一集返回 null`() {
        val q = AutoNext.decode(AutoNext.encode(items))
        assertEquals("第 2 集", AutoNext.next(q, 0)?.title)
        assertEquals("第 3 集", AutoNext.next(q, 1)?.title)
        assertNull(AutoNext.next(q, 2))
    }

    @Test
    fun `下标越界或没有队列都不连播`() {
        val q = AutoNext.decode(AutoNext.encode(items))
        assertNull(AutoNext.next(q, -1))
        assertNull(AutoNext.next(q, 99))
        assertNull(AutoNext.next(emptyList(), 0))
    }

    @Test
    fun `坏队列数据只是不连播 不能崩`() {
        assertTrue(AutoNext.decode("{ broken json").isEmpty())
        assertTrue(AutoNext.decode("一段没有分隔符的文字").isEmpty())
        assertTrue(AutoNext.decode(null).isEmpty())
        assertTrue(AutoNext.decode("").isEmpty())
        // 混进一行脏数据：丢掉它，好的那集照样能连
        val q = AutoNext.decode("bad-line\n" + AutoNext.encode(listOf(items[0])))
        assertEquals(1, q.size)
        assertEquals("/cartoon/s1/01.mp4", q[0].file)
    }

    @Test
    fun `标题和文件名里的特殊字符不会串列`() {
        val weird = Item(id = "p:/a/1|2.mp4", title = "第1集 | 上\n下", file = "/a/1|2.mp4")
        val q = AutoNext.decode(AutoNext.encode(listOf(weird)))
        assertEquals(1, q.size)
        assertEquals("/a/1|2.mp4", q[0].file)
        assertEquals("第1集 | 上\n下", q[0].title)
    }

    @Test
    fun `缺第三段时退回路径当 key`() {
        val one = AutoNext.encode(listOf(items[0])).split('|')
        val q = AutoNext.decode(one[0] + "|" + one[1] + "|")
        assertEquals(1, q.size)
        assertEquals("/cartoon/s1/01.mp4", q[0].key)
    }

    @Test
    fun `三道闸门缺一不可`() {
        assertTrue(AutoNext.allowed(true, true, true, false))
        assertFalse(AutoNext.allowed(false, true, true, false))  // 开关没开
        assertFalse(AutoNext.allowed(true, false, true, false))  // 限额 / 时段 / 远程锁定
        assertFalse(AutoNext.allowed(true, true, false, false))  // 已经是最后一集
        assertFalse(AutoNext.allowed(true, true, true, true))    // 家长按了取消
    }

    @Test
    fun `队列封顶 200 集 不把 Intent 撑爆`() {
        val many = (1..260).map {
            Item(id = "p:/a/$it.mp4", title = "第 $it 集", file = "/a/$it.mp4")
        }
        assertEquals(AutoNext.MAX_QUEUE, AutoNext.decode(AutoNext.encode(many)).size)
    }
}
