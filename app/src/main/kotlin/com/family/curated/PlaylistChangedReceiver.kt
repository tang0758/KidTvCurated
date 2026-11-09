package com.family.curated

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.family.curated.data.PlaylistSync

/**
 * 网页编辑器保存 tv.json 后发来的显式广播，让电视不必等下一轮轮询就刷新。
 * 命令：adb shell am broadcast -a com.family.curated.PLAYLIST_CHANGED -p com.family.curated
 *
 * 这是加速通道，不是唯一通道：广播没送达（电视关机 / adb 没连 / 没装编辑器）
 * 时，应用自己的轮询照样会在 intervalSeconds 内发现变化。
 */
class PlaylistChangedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != PlaylistSync.ACTION_REFRESH) return
        Log.i("PlaylistChanged", "收到清单变更通知，立即检查 NAS")
        PlaylistSync.init(ctx)
        val pending = goAsync()
        PlaylistSync.checkNow("broadcast") {
            try {
                pending.finish()
            } catch (e: Exception) {
                // 系统可能已经超时回收，忽略即可，轮询会兜底
            }
        }
    }
}
