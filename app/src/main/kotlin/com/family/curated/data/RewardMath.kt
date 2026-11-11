package com.family.curated.data

/**
 * 完成必看自动加时（OPT-10）的纯逻辑，单独放这里是为了能跑纯 JVM 单测
 * （不许碰 `android.*` / `org.json`）。
 *
 * 为什么不直接用 [Extras.grantTemp]（ROADMAP 原提案的写法）：
 *
 *  - `grantTemp` 是「临时放行」：它靠 `tempUntil` 这个**从此刻开始倒计时**的窗口，
 *    家长再点一次放行就会覆盖它；孩子做完任务后先去吃饭、晚上才回来看，
 *    那 10 分钟很可能已经过期了 —— 那就不叫奖励，叫抽奖。
 *  - 奖励的语义是「今天多完成了一件事，今天额度多 10 分钟」。所以它应该记在
 *    **当天的上限**上：写 `bonus_<day>`，只加不退，跨中午、跨饭后都还在。
 *  - 家长手动放行（`tempUntil`）与完成奖励（`bonus_<day>`）因此完全分开计数，
 *    谁也不会覆盖谁 —— 这正是 ROADMAP 里点出的那个坑。
 *
 * 日期键一律由 `Extras` 走 `ClockGuard.day()`，改电视日期拿不到明天的奖励。
 */
object RewardMath {

    /**
     * 单次奖励上限 120 分钟。配置是家长手写的（网页编辑器 / 电视设置页），
     * 写错一个 0 变成 1000 就等于把电视变成无限看，这里兜一道底。
     */
    const val MAX_REWARD_MINUTES = 120

    fun clampReward(minutes: Int): Int = minutes.coerceIn(0, MAX_REWARD_MINUTES)

    /**
     * 该不该发奖励。
     *
     * `gateTotal == 0`（片单里没有必看分区）时**不发** —— 否则「没有任务」等于
     * 「一秒完成」，等于天天送时间。`gateLeft > 0` 说明还没做完。
     * [alreadyGiven] 是当天已发过的标记：今天全部完成 → 家长又加了一个必看 →
     * 再做一次，也不再发第二次。
     */
    fun shouldGrant(
        rewardMinutes: Int,
        gateTotal: Int,
        gateLeft: Int,
        alreadyGiven: Boolean
    ): Boolean = clampReward(rewardMinutes) > 0 && gateTotal > 0 && gateLeft <= 0 && !alreadyGiven

    /**
     * 今天实际生效的上限 = 基础上限 + 奖励加时。
     * 基础 0 表示「不按分钟限制」，此时加时没有意义，保持 0
     * （否则 0 + 10 会凭空造出一个 10 分钟上限，比不配置还严格）。
     */
    fun limitWith(baseMinutes: Int, bonusMinutes: Int): Int =
        if (baseMinutes <= 0) 0 else baseMinutes + clampReward(bonusMinutes)

    /** 加时只加不退：重复触发取大值，不会 10 → 20 → 30 滚下去 */
    fun bonusAfter(current: Int, reward: Int): Int =
        maxOf(current.coerceAtLeast(0), clampReward(reward))

    /**
     * 电视上给家长/孩子看的一句话：「今天 30 分钟（含奖励 10 分钟）」。
     * 没有奖励时返回空串，调用方就不显示括号，界面不会被 +0 弄脏。
     */
    fun bonusNote(bonusMinutes: Int): String {
        val b = clampReward(bonusMinutes)
        return if (b > 0) "含奖励 $b 分钟" else ""
    }
}
