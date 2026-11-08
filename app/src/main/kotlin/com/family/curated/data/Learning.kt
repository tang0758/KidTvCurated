package com.family.curated.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 锁屏右半边「等待时间学习角」的内容类型。
 * 四类内容共用一张卡片结构，UI 按 type 换排版。
 */
enum class CardType(val key: String, val label: String) {
    POEM("poem", "唐诗"),
    WORD("word", "英语单词"),
    ART("art", "名画"),
    TEXT("text", "识字 · 短句");

    companion object {
        fun of(k: String?): CardType = values().firstOrNull { it.key == k } ?: TEXT
    }
}

/**
 * 一张学习卡片。字段含义随 type 略有不同：
 *  - poem : title=诗名  sub=朝代·作者  lines=诗句  note=诗意
 *  - word : title=单词  sub=音标/词性 lines=例句  note=中文释义
 *  - art  : title=画名  sub=画家·年代 lines=小知识 note=看点   image=asset:xxx 或 NAS 路径
 *  - text : title=大字  sub=拼音/类别 lines=组词   note=释义
 */
data class LearningCard(
    val type: CardType,
    val title: String,
    val sub: String = "",
    val lines: List<String> = emptyList(),
    val note: String = "",
    val image: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.key)
        put("title", title)
        if (sub.isNotBlank()) put("sub", sub)
        if (lines.isNotEmpty()) put("lines", JSONArray(lines))
        if (note.isNotBlank()) put("note", note)
        if (!image.isNullOrBlank()) put("image", image)
    }

    companion object {
        fun parse(o: JSONObject): LearningCard? {
            val title = o.optString("title").ifBlank { o.optString("text") }
            if (title.isBlank()) return null
            val linesArr = o.optJSONArray("lines") ?: o.optJSONArray("body") ?: JSONArray()
            val lines = ArrayList<String>(linesArr.length())
            for (i in 0 until linesArr.length()) {
                val s = linesArr.optString(i, "").trim()
                if (s.isNotEmpty()) lines.add(s)
            }
            // 兼容把整段文字写进单一 text 字段的简写
            if (lines.isEmpty() && o.optString("body").isNotBlank()) lines.add(o.optString("body"))
            return LearningCard(
                type = CardType.of(o.optString("type").ifBlank { o.optString("kind") }),
                title = title,
                sub = o.optString("sub").ifBlank { o.optString("author") },
                lines = lines,
                note = o.optString("note").ifBlank { o.optString("meaning") },
                image = o.optString("image").ifBlank { null }
            )
        }
    }
}

/**
 * tv.json 里的 "learning" 段。整段不写也会启用内置内容（向后兼容）。
 *
 * enabled=false 时锁屏回到原来的纯倒计时全屏布局；
 * useBuiltin=false 时只显示 custom 里家长自己写的卡片；
 * artFolder 指向 NAS 上的图片目录时，名画卡片会依次用目录里的图片。
 */
data class Learning(
    val enabled: Boolean = true,
    val intervalSeconds: Int = 20,
    val order: List<CardType> = DEFAULT_ORDER,
    val useBuiltin: Boolean = true,
    val artFolder: String = "",
    val artShare: String = "",
    val custom: List<LearningCard> = emptyList()
) {
    /** 每类先各来一张，再按比例平滑穿插，避免同类连着出现一大串 */
    fun deck(): List<LearningCard> {
        val pools = HashMap<CardType, MutableList<LearningCard>>()
        CardType.values().forEach { pools[it] = ArrayList() }
        if (useBuiltin) LearningLibrary.all.forEach { pools[it.type]!!.add(it) }
        custom.forEach { pools[it.type]!!.add(it) }

        val initial = pools.mapValues { it.value.size }
        val total = initial.values.sum()
        if (total == 0) return emptyList()

        val seq = (order.ifEmpty { DEFAULT_ORDER } + CardType.values().toList()).distinct()
        val out = ArrayList<LearningCard>(total)
        var last: CardType? = null
        while (out.size < total) {
            val notEmpty = seq.filter { pools[it]!!.isNotEmpty() }
            if (notEmpty.isEmpty()) break
            val choices = notEmpty.filter { it != last }.ifEmpty { notEmpty }
            val pick = choices.maxByOrNull {
                pools[it]!!.size.toDouble() / initial[it]!!.coerceAtLeast(1)
            } ?: break
            out.add(pools[pick]!!.removeAt(0))
            last = pick
        }
        return out
    }

    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("intervalSeconds", intervalSeconds)
        put("order", JSONArray().also { a -> order.forEach { a.put(it.key) } })
        put("useBuiltin", useBuiltin)
        if (artFolder.isNotBlank()) put("artFolder", artFolder)
        if (artShare.isNotBlank()) put("artShare", artShare)
        if (custom.isNotEmpty()) {
            put("custom", JSONArray().also { a -> custom.forEach { a.put(it.toJson()) } })
        }
    }.toString()

    companion object {
        val DEFAULT_ORDER = listOf(CardType.POEM, CardType.WORD, CardType.ART, CardType.TEXT)

        fun fromJson(s: String?): Learning = try {
            parse(JSONObject(s ?: "{}"))
        } catch (e: Exception) {
            Learning()
        }

        fun parse(o: JSONObject?): Learning {
            if (o == null) return Learning()
            val orderArr = o.optJSONArray("order")
            val order = ArrayList<CardType>()
            if (orderArr != null) {
                for (i in 0 until orderArr.length()) order.add(CardType.of(orderArr.optString(i)))
            }
            val customArr = o.optJSONArray("custom") ?: o.optJSONArray("items") ?: JSONArray()
            val custom = ArrayList<LearningCard>(customArr.length())
            for (i in 0 until customArr.length()) {
                val c = customArr.optJSONObject(i) ?: continue
                LearningCard.parse(c)?.let { custom.add(it) }
            }
            return Learning(
                enabled = o.optBoolean("enabled", true),
                intervalSeconds = o.optInt("intervalSeconds", 20)
                    .coerceIn(6, 600),
                order = order.distinct().ifEmpty { DEFAULT_ORDER },
                useBuiltin = o.optBoolean("useBuiltin", true),
                artFolder = o.optString("artFolder").ifBlank { "" },
                artShare = o.optString("artShare").ifBlank { "" },
                custom = custom
            )
        }
    }
}
