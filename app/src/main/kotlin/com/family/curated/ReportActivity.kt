package com.family.curated

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.family.curated.data.Extras
import com.family.curated.data.KioskKeys
import com.family.curated.data.PlaylistParser
import com.family.curated.data.ReportMath
import com.family.curated.data.Store
import com.family.curated.databinding.ActivityReportBinding

/**
 * 电视端周报（OPT-08）。入口在家长控制页顶部，只读、不给任何「修改」按钮 ——
 * 家长在沙发上想看的是「这周怎么样」，不是再来一堆开关。
 *
 * 数据全部来自本地，没有任何新埋点、也不读 NAS：
 *  - `watch_<day>` / `ext_<day>`：每天总时长与其中第三方应用那段（OPT-04）。
 *    这台 ROM 的 `totalTimeInForeground` 在会话期间不刷新，所以明细是
 *    `elapsedRealtime` 估算出来的，图表下面标注「估算」，别让家长以为是系统账单。
 *  - `stamps`：本周盖了几天章。
 *  - `wplay_<day>`：这周哪天点开过哪部（OPT-08 新增，同一天同一部只记一次）。
 *  - `pos_` / `dur_`：看到一半就退出的片子。
 *
 * 一条记录都没有时**不显示一排 0**：0 会被读成「孩子真的一分钟没看」，
 * 而实际可能只是今天刚装好 App。
 */
class ReportActivity : AppCompatActivity() {

    private lateinit var b: ActivityReportBinding

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KioskKeys.systemBlocked(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Extras.init(this)
        b = ActivityReportBinding.inflate(layoutInflater)
        setContentView(b.root)
        render()
    }

    override fun onResume() {
        super.onResume()
        // 从播放页回来时数字可能已经变了
        render()
    }

    private fun render() {
        val today = Extras.day()
        val rows = ReportMath.buildWeek(
            today = today,
            watchMs = { Extras.watchMsOn(it) },
            extMs = { Extras.extMsOn(it) },
            doneOf = { Extras.isCheckedIn(it) }
        )
        val limitMinutes = Extras.schedule()?.dailyLimitMinutes ?: 0
        val limitMs = limitMinutes * 60_000L
        val scale = ReportMath.scaleMax(rows, limitMs)
        val empty = ReportMath.isEmpty(rows)

        b.tvRange.text = ReportMath.weekRangeText(today)
        b.tvTotal.text = if (empty) "本周还没有记录"
        else "本周 ${ReportMath.durationText(ReportMath.totalMs(rows))}"

        // 一条都没有时传空列表，让 View 自己画「这周还没有记录」而不是 7 根 0 高度的柱子
        b.chart.setData(if (empty) emptyList() else rows, scale, limitMs)

        b.tvChartHint.text = buildString {
            append("纵轴最高 ${ReportMath.durationText(scale)}")
            if (limitMs > 0L) append(" · 虚线是每天上限 $limitMinutes 分钟")
            if (!empty) {
                append(" · 平均 ${ReportMath.durationText(ReportMath.averageMs(rows))}/天")
                val ext = ReportMath.extMs(rows)
                if (ext > 0L) append(" · 第三方应用 ${ReportMath.durationText(ext)}（按使用时长估算）")
            }
        }

        b.tvStreak.text = "连续 ${Extras.streak()} 天"
        b.tvStreakSub.text = buildString {
            append("最长连击 ${Extras.bestStreak()} 天 · 本周盖章 ${ReportMath.doneDays(rows)} 天\n")
            append("累计打卡 ${Extras.stamps().size} 天 · 累计看完 ${Extras.videosTotal()} 个视频")
            if (!empty) {
                append("\n本周看完 ${rows.sumOf { Extras.videosOn(it.date) }} 个")
            }
        }

        val names = playlistTitles()
        fun nameOf(key: String): String = names[key] ?: ReportMath.pathTitle(key)

        val repeat = ReportMath.topRepeated(Extras.playedDaysOn(rows.map { it.date }))
        b.tvRepeat.text = if (repeat.isEmpty()) {
            "反复看：这周还没有同一部看两天的记录"
        } else {
            "反复看：" + repeat.joinToString("、") { "${nameOf(it.key)}（${it.value} 天）" }
        }

        val half = ReportMath.halfWatched(Store.allProgress())
        b.tvHalf.text = if (half.isEmpty()) {
            "看到一半：没有中途退出的片子"
        } else {
            "看到一半：\n" + half.joinToString("\n") { "· ${nameOf(it.key)} 只看到 ${it.value}%" }
        }
    }

    /**
     * 用清单缓存把「路径派生 key」翻成家长自己填的标题。
     * 读不到缓存（还没成功连过 NAS）就退化成文件名，周报不能因为读不到片单就打不开。
     */
    private fun playlistTitles(): Map<String, String> {
        val raw = Store.cachedPlaylist ?: return emptyMap()
        val pl = runCatching { PlaylistParser.parse(raw) }.getOrNull() ?: return emptyMap()
        return pl.sections.flatMap { it.items }.associate { it.key() to it.title }
    }
}
