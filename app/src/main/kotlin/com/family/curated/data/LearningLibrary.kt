package com.family.curated.data

/**
 * 内置离线题库总入口：不联网、不依赖 NAS，装上 APK 锁屏就能看。
 *
 * 分类源码放在 data/builtin/ 下（Poems / Words / Arts / Texts），
 * 想加内容只改对应文件即可；家长也可以在 tv.json 的 learning.custom 里追加自己的卡片，
 * 或把 useBuiltin 设成 false 只用自定义内容。
 *
 * 名画图片放在 assets/art/ 下（公共领域作品，来源见 docs/ART_SOURCES.md）。
 */
object LearningLibrary {

    val poems = BuiltinPoems.all
    val words = BuiltinWords.all
    val arts = BuiltinArts.all
    val texts = BuiltinTexts.all

    val all: List<LearningCard> = poems + words + arts + texts

    fun countByType(): Map<CardType, Int> =
        CardType.values().associateWith { t -> all.count { it.type == t } }
}
