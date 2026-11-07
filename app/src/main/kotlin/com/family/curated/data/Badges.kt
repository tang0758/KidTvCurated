package com.family.curated.data

/**
 * 勋章规则全部由「累计数据」推导，不需要额外存档。
 * 徽记用中文字，电视上的系统字体一定能显示。
 */
data class Badge(
    val id: String,
    val glyph: String,
    val name: String,
    val desc: String,
    val goal: Int,
    val progress: (Stats) -> Int
)

data class Stats(
    val totalDays: Int,
    val streak: Int,
    val bestStreak: Int,
    val totalMinutes: Int,
    val totalVideos: Int,
    val todayMinutes: Int
)

object Badges {

    val all: List<Badge> = listOf(
        Badge("first", "初", "启程之星", "首次完成每日必看", 1) { it.totalDays },
        Badge("d3", "叁", "三日之约", "连续打卡 3 天", 3) { it.bestStreak },
        Badge("d7", "柒", "一周坚持", "连续打卡 7 天", 7) { it.bestStreak },
        Badge("d15", "旬", "半月小达人", "打卡满 15 天", 15) { it.totalDays },
        Badge("d30", "月", "满月传说", "打卡满 30 天", 30) { it.totalDays },
        Badge("v10", "十", "观影新星", "看完 10 个视频", 10) { it.totalVideos },
        Badge("v50", "百", "百集勇士", "看完 50 个视频", 50) { it.totalVideos },
        Badge("h5", "时", "时间旅行者", "累计看 5 小时", 300) { it.totalMinutes },
        Badge("h20", "恒", "资深观众", "累计看 20 小时", 1200) { it.totalMinutes },
        Badge("perfect", "冠", "完美一周", "当前连击 7 天", 7) { it.streak }
    )

    fun stats(): Stats = Stats(
        totalDays = Extras.stamps().size,
        streak = Extras.streak(),
        bestStreak = Extras.bestStreak(),
        totalMinutes = GateKeeper.minutes(Extras.watchMsTotal()),
        totalVideos = Extras.videosTotal(),
        todayMinutes = GateKeeper.minutes(Extras.watchMsToday())
    )

    fun unlocked(s: Stats = stats()): List<Badge> = all.filter { it.progress(s) >= it.goal }

    fun unlockedIds(): Set<String> = unlocked().map { it.id }.toSet()

    /** 还没在勋章墙里看过的、新解锁的勋章数量（主界面用来挂小红点） */
    fun newCount(): Int {
        val seen = Extras.seenBadges()
        return unlockedIds().count { it !in seen }
    }
}
