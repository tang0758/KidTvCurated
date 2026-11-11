package com.family.curated

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.family.curated.data.Diag
import com.family.curated.data.Extras

/**
 * 开机自启：只在 kiosk.enabled 时生效。
 *
 * 有些电视（海信 VIDAA 尤其明显）开机后不会立刻启动默认桌面，而是先落在
 * 厂商桌面或某个应用的 BOOT_COMPLETED 自启上。孩子在这几秒里能乱点，
 * 所以小剧场自己监听开机广播，主动回到前台；之后由 KidApp 的前台守卫兜底。
 *
 * 没开儿童锁的普通用户不会受到任何影响，装完不会开机自动弹出来。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_HISENSE_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != ACTION_HTC_QUICKBOOT_POWERON
        ) return

        try {
            Extras.init(context)
            if (!Extras.kiosk().enabled) return
            // 家长临时放行期间不要把自己又拉回来
            if (Extras.kioskExitActive()) return
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
            )
        } catch (e: Exception) {
            // 个别 ROM 限制后台启动 Activity，忽略即可：
            // 默认桌面 + KidApp 前台守卫仍会在按 Home 或切后台时兜底。
            // 但「开机没起来」是家长最常反馈的那一类，得留下记录（OPT-17）
            try {
                Diag.init(context)
                Diag.warn("boot", "开机自启失败（儿童锁可能没及时生效）", e)
            } catch (ignored: Throwable) {
            }
        }
    }

    private companion object {
        /** 海信 VIDAA 私有开机广播：部分机型不向第三方派发标准 BOOT_COMPLETED */
        const val ACTION_HISENSE_BOOT_COMPLETED = "android.intent.action.HISENSE_BOOT_COMPLETED"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
