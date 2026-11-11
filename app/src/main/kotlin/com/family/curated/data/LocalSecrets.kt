package com.family.curated.data

import com.family.curated.BuildConfig

/**
 * 首次启动时填进设置页的默认 NAS 账号。
 *
 * **真实账号密码不进 Git**，两条来源，编译期由 `app/build.gradle.kts` 编进 `BuildConfig`：
 *  1. `local.properties`（已 gitignore）里的 `nas.user` / `nas.pass` —— 本机开发用；
 *  2. 环境变量 `NAS_USER` / `NAS_PASS` —— CI 或临时构建用。
 *
 * 两边都没有时是**空串**：这样仓库 clone 下来就能直接编译（GitHub Actions 就是这种情形），
 * 代价只是首次启动要在设置页手填 NAS 账号。
 *
 * 为什么不再用一个 gitignore 掉的 `LocalSecrets.kt` 存明文：那会让**仓库本身不可构建** ——
 * 除了你本机，谁都编不过，CI 更是一上去就红（2026-11-11 第一次跑 CI 就栽在这）。
 * 字符串常量本来就会进 APK，改成 `BuildConfig` 不增加任何泄露面。
 */
object LocalSecrets {
    val USER: String = BuildConfig.NAS_USER
    val PASS: String = BuildConfig.NAS_PASS

    /** 两个都空 = 没配默认账号，首次启动由家长在设置页填 */
    val isConfigured: Boolean get() = USER.isNotBlank() && PASS.isNotBlank()
}
