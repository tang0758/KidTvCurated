package com.family.curated.settings

import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * 分组能用到的、由设置页提供的几件事。
 *
 * 拆页时最容易出的事是「弹窗在哪、写回在哪」两头都要用：分组只描述「点了之后干什么」，
 * 真正落盘、重绘、占位状态留给 [AppCompatActivity] 那一侧，避免每个分组各写一份
 * `PlaylistWriter.patch` + 错误条逻辑。
 *
 * 注意 [save] 的语义是 OPT-07 定下的：**写成功才更新本机**，失败只挂错误条。
 */
interface SettingsHost {

    /** 宿主页面：弹窗、Toast、startActivity 都用它 */
    val act: AppCompatActivity

    /** 取文案；带 [args] 时按位置格式化（`%1$d` / `%2$s`）。参数可空，和 `Context.getString` 一致 */
    fun str(id: Int, vararg args: Any?): String

    /**
     * 改 NAS 上的 tv.json。
     * [apply] 在后台线程拿到最新 JSON 后执行；[after] 只在写成功后跑（更新本机缓存）；
     * [label] 是失败时记给家长看的「本想改什么」。
     */
    fun save(apply: (JSONObject) -> Unit, after: () -> Unit = {}, label: String? = null)

    /** 重新渲染整页（读最新状态、保留焦点） */
    fun refresh()

    /** 占住底部状态条；已有任务在跑时返回 false，调用方直接放弃这次操作 */
    fun beginTask(status: String): Boolean

    /** 放开底部状态条，恢复显示页脚 */
    fun endTask()

    fun toast(text: String, long: Boolean = false)
}
