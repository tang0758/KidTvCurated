package com.family.curated.settings

/**
 * 设置页的一行：要么是分组小标题，要么是可点的设置项。
 *
 * 整页只是一串 [Entry]，适配器不关心业务，分组也不关心怎么渲染 ——
 * 所以加一个设置项只要往对应分组的 `rows()` 里塞一行，不用碰列表代码。
 */
sealed class Entry {
    data class Group(val title: String) : Entry()
    data class Row(
        val title: String,
        val hint: String,
        val value: String,
        val click: () -> Unit
    ) : Entry()
}

/**
 * 一个分组（时间与时长 / 规则 / 维护 / 系统 …）能产出的行。
 *
 * 分组自己决定要不要出这一组：例如「周报」被 `features.report` 关掉时，
 * 概览组整组不出现，连标题都不留一个空壳。
 */
interface SettingsGroup {
    fun rows(): List<Entry>
}
