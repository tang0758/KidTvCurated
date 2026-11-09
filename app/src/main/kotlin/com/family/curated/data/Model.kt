package com.family.curated.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * NAS 连接配置
 */
data class NasConfig(
    val host: String,
    val port: Int = 445,
    val user: String,
    val password: String,
    val share: String
)

/**
 * 片单里的一个条目。
 *
 * 两种用法（二选一）：
 *  - file   : 直接指向一个视频文件
 *  - folder : 指向一个文件夹，点进去后列出里面所有视频（适合整季动画）
 *
 * cover 可选：指向一张封面图。
 *  - "xxx.jpg"                相对视频共享的路径
 *  - "data:curated/covers/x.jpg"  带共享名前缀，用于视频共享只读、封面放在可写共享的情况
 *  - 不写则自动在视频同目录找同名 .jpg / .png / folder.jpg / poster.jpg / cover.jpg / 封面.jpg
 *  - 都找不到就显示标题文字占位卡
 */
/** 封面候选。share 为 null 表示用清单默认的视频共享 */
data class CoverRef(val path: String, val share: String? = null)

data class Item(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val file: String? = null,
    val folder: String? = null,
    val cover: String? = null,
    /** 非空表示这是一个第三方应用入口，值为包名或「包名/Activity」 */
    val app: String? = null
) {
    val isFolder: Boolean get() = folder != null
    fun key(): String = id.ifEmpty { (file ?: folder ?: app ?: title) }
}

/**
 * 分区。gate = true 表示这是"今日必修"，必须全部看完才解锁后面 requires 指向它的分区。
 */
data class Section(
    val key: String,
    val title: String,
    val gate: Boolean = false,
    val requires: String? = null,
    val columns: Int = 5,
    val items: List<Item> = emptyList(),
    /** 自动轮换：从这个文件夹里每天取 count 个，按天推进 */
    val autoFolder: String? = null,
    val autoCount: Int = 2
)

/**
 * 清单自动刷新策略。写在 tv.json 顶层，不写就全部用默认值（开启 / 15 秒 / 清单优先）。
 *
 * - enabled        关掉后只在冷启动、家长菜单、广播时读清单
 * - intervalSeconds 前台轮询间隔（5~3600 秒）；只在内容真的变了才重绘
 * - followAppList  清单 apps 段变化时，自动清掉电视上家长菜单的本地勾选，
 *                  让网页里改的应用列表立刻生效（否则本地勾选会一直盖住 entries）
 */
data class AutoRefresh(
    val enabled: Boolean = true,
    val intervalSeconds: Int = 15,
    val followAppList: Boolean = true
) {
    companion object {
        fun parse(o: JSONObject?): AutoRefresh {
            if (o == null) return AutoRefresh()
            return AutoRefresh(
                enabled = o.optBoolean("enabled", true),
                intervalSeconds = o.optInt("intervalSeconds", 15).coerceIn(5, 3600),
                followAppList = o.optBoolean("followAppList", true)
            )
        }
    }
}

data class Playlist(
    val nas: NasConfig?,
    val sections: List<Section>,
    val pin: String = "1234",
    val passRatio: Float = 0.92f,  // 播放进度达到这个比例就算"看完"
    val schedule: Schedule? = null, // 可用时段 / 每日时长上限；不写就不限制
    val learning: Learning? = null,  // 锁屏右侧学习角；不写 = 启用内置题库
    val kiosk: Kiosk = Kiosk(),      // 儿童锁；不写 = 关闭，行为与旧版一致
    val apps: AppsConfig = AppsConfig(), // 家长允许的应用入口；不写 = 不显示
    val autoRefresh: AutoRefresh = AutoRefresh() // 清单变更自动同步策略
) {
    val gateSections: List<Section> get() = sections.filter { it.gate }
}

object PlaylistParser {

    /**
     * 兼容简写：也可以直接把 allowed / dailyLimitMinutes / lockMessage / pin 写在顶层。
     * 有 schedule 段时以 schedule 为准。
     */
    private fun scheduleFromLegacy(root: JSONObject): JSONObject? {
        val hasAny = root.has("allowed") || root.has("allowedHours") ||
            root.has("dailyLimitMinutes") || root.has("lockMessage")
        if (!hasAny) return null
        return JSONObject().apply {
            put("enabled", true)
            val arr = root.optJSONArray("allowed") ?: root.optJSONArray("allowedHours")
            if (arr != null) {
                put("weekday", arr)
                put("weekend", arr)
            }
            put("dailyLimitMinutes", root.optInt("dailyLimitMinutes", 0))
            if (root.has("lockMessage")) put("lockMessage", root.optString("lockMessage"))
            if (root.has("pin")) put("pin", root.optString("pin"))
        }
    }

    fun parse(json: String): Playlist {
        val root = JSONObject(json)

        val nas = root.optJSONObject("nas")?.let {
            NasConfig(
                host = it.optString("host", "192.168.31.72"),
                port = it.optInt("port", 445),
                user = it.optString("user", ""),
                password = it.optString("password", ""),
                share = it.optString("share", "")
            )
        }

        val arr: JSONArray = root.optJSONArray("sections") ?: JSONArray()
        val sections = ArrayList<Section>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val key = s.optString("key", "section$i")
            val itemsArr = s.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<Item>(itemsArr.length())
            for (j in 0 until itemsArr.length()) {
                val o = itemsArr.getJSONObject(j)
                val file = o.optString("file").ifBlank { null }
                val folder = o.optString("folder").ifBlank { null }
                val app = o.optString("app").ifBlank { o.optString("package").ifBlank { null } }
                if (file == null && folder == null && app == null) continue
                // 没写 id 时，用「路径」派生稳定 id，绝对不要用下标：
                // key() 是封面缓存、观看进度、今日完成标记的键，用 key_j 的话
                // 家长在编辑器里上下调一次顺序，所有封面和进度就整体错位了。
                val autoId = (file ?: folder ?: app).orEmpty().trimEnd('/').let {
                    if (it.isNotEmpty()) "p:" + it else key + "_" + j
                }
                items.add(
                    Item(
                        id = o.optString("id").ifBlank { autoId },
                        title = o.optString("title").ifBlank {
                            if (app != null) app.substringBefore('/')
                            else (file ?: folder ?: "").substringAfterLast('/')
                        },
                        subtitle = o.optString("subtitle").ifBlank { null },
                        file = file,
                        folder = folder,
                        cover = o.optString("cover").ifBlank { null },
                        app = app
                    )
                )
            }
            val auto = s.optJSONObject("auto")
            sections.add(
                Section(
                    key = key,
                    title = s.optString("title", key),
                    gate = s.optBoolean("gate", false),
                    requires = s.optString("requires").ifBlank { null },
                    columns = s.optInt("columns", 5),
                    items = items,
                    autoFolder = auto?.optString("folder")?.ifBlank { null },
                    autoCount = auto?.optInt("count", 2) ?: 2
                )
            )
        }

        return Playlist(
            nas = nas,
            sections = sections,
            pin = root.optString("pin", "1234"),
            passRatio = root.optDouble("passRatio", 0.92).toFloat(),
            schedule = Schedule.parse(
                root.optJSONObject("schedule") ?: scheduleFromLegacy(root)
            ),
            learning = Learning.parse(root.optJSONObject("learning")),
            kiosk = Kiosk.parse(root.optJSONObject("kiosk")),
            apps = AppsConfig.parse(root.optJSONObject("apps")),
            autoRefresh = AutoRefresh.parse(root.optJSONObject("autoRefresh"))
        )
    }
}
