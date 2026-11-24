package com.family.curated.data

import org.json.JSONObject

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

    /**
     * 仅解析 NAS `learning.json` 中的在线单词（不兜底内置词库），供锁屏学习角按需复用。
     */
    fun parseOnlineWordsOnly(jsonText: String?): List<LearningCard> {
        if (jsonText.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(jsonText)
            val arr = root.optJSONArray("cards") ?: return emptyList()
            val out = ArrayList<LearningCard>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val c = LearningCard.parse(obj) ?: continue
                if (c.type == CardType.WORD) out.add(c)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 解析 NAS `learning.json` 中的词库与每日词数；
     * 断网 / 解析失败 / 无单词时自动兜底内置 78 词（FR-35）。
     */
    fun loadOnlineWords(jsonText: String?): Pair<List<LearningCard>, Int> {
        if (!jsonText.isNullOrBlank()) {
            try {
                val root = JSONObject(jsonText)
                val dailyCount = root.optInt("dailyWordCount", WordCheckInMath.DEFAULT_DAILY_COUNT)
                    .coerceIn(1, 50)
                val out = parseOnlineWordsOnly(jsonText)
                if (out.isNotEmpty()) return out to dailyCount
            } catch (_: Exception) {
                // 解析失败静默兜底内置词库
            }
        }
        return words to WordCheckInMath.DEFAULT_DAILY_COUNT
    }

    /**
     * 读取每日汉字打卡字库：优先使用 tv.json 的 learning.custom 中的识字卡（方案 B），
     * 未配置时兜底内置 70 张识字/短句卡。
     */
    fun loadHanziCards(learning: Learning): List<LearningCard> {
        val customTexts = learning.custom.filter { it.type == CardType.TEXT }
        return if (customTexts.isNotEmpty()) customTexts else texts
    }
}
