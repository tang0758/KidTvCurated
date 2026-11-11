package com.family.curated.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPT-10 完成必看奖励加时的纯逻辑。
 * 这些用例覆盖的是「什么时候该给、给多少、加了之后今天上限是多少」，
 * 真正记账与发 Toast 在 Extras / MainActivity，靠实机验证。
 */
class RewardMathTest {

    @Test
    fun `必看全部完成才发 奖励配置为 0 时不发`() {
        assertTrue(RewardMath.shouldGrant(10, gateTotal = 3, gateLeft = 0, alreadyGiven = false))
        assertFalse(RewardMath.shouldGrant(0, gateTotal = 3, gateLeft = 0, alreadyGiven = false))
        assertFalse(RewardMath.shouldGrant(10, gateTotal = 3, gateLeft = 1, alreadyGiven = false))
    }

    @Test
    fun `没有必看分区时不发 否则等于天天白送`() {
        assertFalse(RewardMath.shouldGrant(10, gateTotal = 0, gateLeft = 0, alreadyGiven = false))
    }

    @Test
    fun `同一天只发一次`() {
        assertFalse(RewardMath.shouldGrant(10, gateTotal = 2, gateLeft = 0, alreadyGiven = true))
    }

    @Test
    fun `奖励分钟数被夹到 0 到 120`() {
        assertEquals(0, RewardMath.clampReward(-5))
        assertEquals(0, RewardMath.clampReward(0))
        assertEquals(120, RewardMath.clampReward(120))
        assertEquals(120, RewardMath.clampReward(9999))
        // 配置写坏成 1000 时也不能把电视变成无限看
        assertEquals(120, RewardMath.clampReward(1000))
    }

    @Test
    fun `上限等于基础加奖励`() {
        assertEquals(70, RewardMath.limitWith(baseMinutes = 60, bonusMinutes = 10))
        assertEquals(60, RewardMath.limitWith(baseMinutes = 60, bonusMinutes = 0))
    }

    @Test
    fun `基础上限为 0 表示不限制 加时不能凭空造出上限`() {
        // 0 + 10 若当成 10 分钟，反而比「不限制」更严格
        assertEquals(0, RewardMath.limitWith(baseMinutes = 0, bonusMinutes = 10))
    }

    @Test
    fun `重复触发取大值 不累加`() {
        assertEquals(10, RewardMath.bonusAfter(current = 0, reward = 10))
        assertEquals(10, RewardMath.bonusAfter(current = 10, reward = 10))
        // 家长当天把奖励从 10 调到 20：按大的来，不是 30
        assertEquals(20, RewardMath.bonusAfter(current = 10, reward = 20))
        assertEquals(20, RewardMath.bonusAfter(current = 20, reward = 5))
    }

    @Test
    fun `奖励提示文案 0 分钟时不显示括号`() {
        assertEquals("", RewardMath.bonusNote(0))
        assertEquals("含奖励 10 分钟", RewardMath.bonusNote(10))
        assertEquals("含奖励 120 分钟", RewardMath.bonusNote(9999))
    }
}
