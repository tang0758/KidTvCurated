package com.family.curated

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 界面层轻量异步调度：统一走协程 Dispatchers.IO，替代散落的裸 Thread + runOnUiThread。
 * 回调主线程前自动检查 Activity 是否已销毁，避免慢速 SMB 操作返回时触碰已回收的视图。
 */
object UiAsync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun <T> run(
        activity: Activity,
        io: () -> T,
        onMain: (T) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { io() }
            if (!activity.isFinishing && !activity.isDestroyed) {
                onMain(result)
            }
        }
    }
}
