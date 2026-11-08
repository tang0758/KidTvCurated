package com.family.curated.data

import android.view.KeyEvent
import org.json.JSONObject

/**
 * 儿童锁（Kiosk）配置，对应 tv.json 的 "kiosk" 段。
 *
 * 重要前提：Android 的 Home 键由系统独占，普通应用收不到、也拦不住。
 * 所以这里做的是三件应用层真能做的事：
 *  1. 吞掉能收到的返回键 / 系统功能键；
 *  2. 声明自己可以当桌面（配合 adb 设为默认桌面后，Home 键会回到自己）；
 *  3. 一旦应用被切到后台，前台守卫在 reentryMs 后自动拉回。
 *
 * 默认 enabled=false，老配置完全不受影响。家长出口走「菜单键 + PIN」，
 * 放行 exitMinutes 分钟内守卫不拉回，避免把自己也锁在电视外面。
 */
data class Kiosk(
    val enabled: Boolean = false,
    /** 家长退出时要启动的桌面组件，如 "app.orchard.tv/.MainActivity"；留空则弹出候选列表 */
    val exitComponent: String = "",
    /** 家长临时放行时长（分钟） */
    val exitMinutes: Int = 30,
    /** 离开应用多久后拉回（毫秒） */
    val reentryMs: Long = 1000L,
    /** 是否拦截返回键 */
    val blockBack: Boolean = true,
    /** 是否拦截能收到的系统功能键（设置、输入源、最近任务、语音等） */
    val blockSystemKeys: Boolean = true
) {
    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        if (exitComponent.isNotBlank()) put("exitComponent", exitComponent)
        put("exitMinutes", exitMinutes)
        put("reentryMs", reentryMs)
        put("blockBack", blockBack)
        put("blockSystemKeys", blockSystemKeys)
    }.toString()

    companion object {
        fun fromJson(s: String?): Kiosk = try {
            parse(JSONObject(s ?: "{}"))
        } catch (e: Exception) {
            Kiosk()
        }

        fun parse(o: JSONObject?): Kiosk {
            if (o == null) return Kiosk()
            return Kiosk(
                enabled = o.optBoolean("enabled", false),
                exitComponent = o.optString("exitComponent").ifBlank { "" },
                exitMinutes = o.optInt("exitMinutes", 30).coerceIn(1, 24 * 60),
                reentryMs = o.optLong("reentryMs", 1000L).coerceIn(300L, 10_000L),
                blockBack = o.optBoolean("blockBack", true),
                blockSystemKeys = o.optBoolean("blockSystemKeys", true)
            )
        }
    }
}

/**
 * 各界面统一调用的按键过滤。只拦「会跳出应用或拉起系统功能」的键，
 * 方向键 / OK / 数字键 / 播放控制键一律放行，锁屏输 PIN 和看片不受影响。
 */
object KioskKeys {

    private val SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_HOME,            // 一般收不到，写上也无妨
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_TV_INPUT,
        KeyEvent.KEYCODE_TV_INPUT_HDMI_1,
        KeyEvent.KEYCODE_TV_INPUT_HDMI_2,
        KeyEvent.KEYCODE_TV_INPUT_HDMI_3,
        KeyEvent.KEYCODE_TV_INPUT_COMPONENT_1,
        KeyEvent.KEYCODE_TV_INPUT_COMPOSITE_1,
        KeyEvent.KEYCODE_TV_INPUT_VGA_1
    )

    /** 主界面用：返回键也要吞掉，否则一路退就回到桌面了 */
    fun blocked(keyCode: Int): Boolean {
        val k = Extras.kiosk()
        if (!k.enabled) return false
        if (k.blockBack && keyCode == KeyEvent.KEYCODE_BACK) return true
        return k.blockSystemKeys && keyCode in SYSTEM_KEYS
    }

    /** 子界面用：返回键要能回上一级，只拦会跳出应用的系统键 */
    fun systemBlocked(keyCode: Int): Boolean {
        val k = Extras.kiosk()
        return k.enabled && k.blockSystemKeys && keyCode in SYSTEM_KEYS
    }
}
