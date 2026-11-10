package com.family.curated.data

/**
 * OPT-13 占位封面的纯计算部分（可单测，不依赖 android.graphics）。
 * 色相由条目 key 派生：同一个视频每次生成的底色都一样，孩子不会因为刷新而「换卡片」。
 */
object PlaceholderMath {
    fun hueOf(key: String): Int = (key.hashCode() and 0x7fffffff) % 360

    /** 标题按每行几个字折行，最多 3 行，最后一行超长用省略号收尾 */
    fun lines(title: String, perLine: Int = 7, maxLines: Int = 3): List<String> {
        val t = title.trim().ifBlank { "小剧场" }
        val out = ArrayList<String>(maxLines)
        var rest = t
        while (rest.isNotEmpty() && out.size < maxLines) {
            if (rest.length <= perLine) {
                out.add(rest)
                break
            }
            if (out.size == maxLines - 1) {
                out.add(rest.take(perLine - 1) + "…")
                break
            }
            out.add(rest.take(perLine))
            rest = rest.drop(perLine)
        }
        return out
    }
}
