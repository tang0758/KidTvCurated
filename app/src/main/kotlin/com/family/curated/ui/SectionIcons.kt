package com.family.curated.ui

import com.family.curated.R

/**
 * OPT-14：分区图标只用内置这几套矢量，家长在编辑器里选名字，不传图片路径 ——
 * 电视不用再为几个小图标去连 SMB，也不会因为写错路径崩在 inflate 里。
 * 不认识的名字一律当「不显示」，和 features 的容错约定一致。
 */
object SectionIcons {

    val NAMES: List<String> = listOf("none", "star", "play", "book", "heart", "game", "moon")

    val LABELS: Map<String, String> = linkedMapOf(
        "none" to "不显示",
        "star" to "星星",
        "play" to "播放",
        "book" to "书本",
        "heart" to "爱心",
        "game" to "游戏",
        "moon" to "月亮"
    )

    fun resId(name: String?): Int? = when (name) {
        "star" -> R.drawable.ic_sec_star
        "play" -> R.drawable.ic_sec_play
        "book" -> R.drawable.ic_sec_book
        "heart" -> R.drawable.ic_sec_heart
        "game" -> R.drawable.ic_sec_game
        "moon" -> R.drawable.ic_sec_moon
        else -> null
    }
}
