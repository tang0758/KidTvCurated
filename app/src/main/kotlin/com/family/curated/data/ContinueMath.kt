package com.family.curated.data

/**
 * OPT-12 首页「继续看」的纯逻辑部分（可单测）。
 *
 * 最容易出错的是「不错位」：分区里的条目 key 一律沿用原来路径派生的 key，
 * 绝不用「第几个有进度」当下标。家长在编辑器里把条目上移/下移、删掉一行，
 * 继续看仍然指向同一个视频、接得上同一段进度。
 */
object ContinueMath {
    /** 动态分区标识固定，焦点复位 / 滚动位置记忆才有稳定键 */
    const val SECTION_KEY = "p:continue"
    const val MAX_ITEMS = 5
    const val SECTION_TITLE = "继续看"

    /** 只认「真看过一段、又没看完」的条目；刚点开就退出、已看完的都不进这一行 */
    fun eligible(
        keys: List<String>,
        progress: (String) -> Long,
        duration: (String) -> Long
    ): List<String> = keys.filter { k ->
        val p = progress(k)
        val d = duration(k)
        p > 5_000L && (d <= 0L || p < d - 15_000L)
    }.take(MAX_ITEMS)

    /**
     * 把「最近看过」的 key 还原成可点的条目。
     * 清单里有的直接用原条目（保留家长写的标题和封面）；剧集页动态列出来的单集
     * 清单里没有，就按路径现造一个，但必须确认它还在当前清单的某个 file / folder 下面，
     * 免得片单删了内容，首页还留着一张点了打不开的卡。
     */
    fun itemFor(
        key: String,
        known: Map<String, Item>,
        exactFiles: Set<String>,
        folders: List<String>
    ): Item? {
        known[key]?.let { return it }
        val path = key.removePrefix("p:")
        if (path.isBlank() || !path.contains('/') || path.contains("..")) return null
        val inList = path in exactFiles || folders.any { path.startsWith(it.trimEnd('/') + "/") }
        if (!inList) return null
        return Item(id = key, title = titleOf(path), file = path)
    }

    /** 没有清单标题时，把乱糟糟的文件名收拾成能看的两个字～一行短标题 */
    fun titleOf(path: String): String {
        val name = path.substringAfterLast('/')
        var n = name.substringBeforeLast('.', name)
        n = n.replace(Regex("【[^】]*】"), "")
        n = n.replace(Regex("[._]+"), " ")
        n = n.replace(
            Regex("(?i)\\b(1080p|720p|2160p|4k|web-?dl|webrip|bluray|x264|h\\.?264|h\\.?265|aac|dts)\\b"),
            " "
        )
        n = n.replace(Regex("\\s{2,}"), " ").trim()
        return n.take(24).ifBlank { name.take(24) }
    }
}
