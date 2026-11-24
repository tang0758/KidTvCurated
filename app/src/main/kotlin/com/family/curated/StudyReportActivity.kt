package com.family.curated

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.family.curated.data.CardType
import com.family.curated.data.ClockGuard
import com.family.curated.data.Extras
import com.family.curated.data.KioskKeys
import com.family.curated.data.LearningLibrary
import com.family.curated.data.Store
import com.family.curated.data.StudyReportMath
import com.family.curated.databinding.ActivityStudyReportBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电视端家长学情总览页（精简版）：
 * 展示四类卡片（英语/汉字/唐诗/名画）掌握进度、薄弱项及近 7 天新掌握动态。
 * 完整明细筛选与薄弱清单导出在 8765 网页控制台「学情反馈」页。
 */
class StudyReportActivity : AppCompatActivity() {

    private lateinit var b: ActivityStudyReportBinding
    private val dateFmt = SimpleDateFormat("MM-dd", Locale.CHINA)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KioskKeys.systemBlocked(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Extras.init(this)
        b = ActivityStudyReportBinding.inflate(layoutInflater)
        setContentView(b.root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val learning = Extras.learning()
        val (words, _) = LearningLibrary.loadOnlineWords(Store.cachedLearningJson)
        val hanzi = LearningLibrary.loadHanziCards(learning)
        val poems = learning.custom.filter { it.type == CardType.POEM }.ifEmpty { LearningLibrary.poems }
        val arts = learning.custom.filter { it.type == CardType.ART }.ifEmpty { LearningLibrary.arts }

        val report = StudyReportMath.build(
            words = words,
            hanzi = hanzi,
            poems = poems,
            arts = arts,
            memories = Extras.allWordMemories(),
            timestamps = Extras.allMasteryTimestamps(),
            todayDayKey = ClockGuard.day(),
            nowMs = ClockGuard.now()
        )

        val totalPct = if (report.totalCards > 0) report.totalMastered * 100 / report.totalCards else 0
        b.tvStudySummaryBadge.text = getString(
            R.string.study_summary_badge,
            report.totalMastered, report.totalCards, totalPct
        )

        fun bindStat(
            type: CardType,
            statTv: android.widget.TextView,
            pb: android.widget.ProgressBar,
            subTv: android.widget.TextView
        ) {
            val s = report.byType.firstOrNull { it.type == type } ?: return
            statTv.text = getString(R.string.study_stat_ratio, s.mastered, s.total, s.percent)
            pb.progress = s.percent
            subTv.text = getString(R.string.study_stat_sub, s.learning, s.unmarked)
        }

        bindStat(CardType.WORD, b.tvWordStat, b.pbWord, b.tvWordSub)
        bindStat(CardType.TEXT, b.tvHanziStat, b.pbHanzi, b.tvHanziSub)
        bindStat(CardType.POEM, b.tvPoemStat, b.pbPoem, b.tvPoemSub)
        bindStat(CardType.ART, b.tvArtStat, b.pbArt, b.tvArtSub)

        b.tvWeakTitle.text = getString(R.string.study_weak_title_count, report.weakItems.size)
        b.tvWeakList.text = if (report.weakItems.isEmpty()) {
            getString(R.string.study_weak_empty)
        } else {
            val shown = report.weakItems.take(18).joinToString("   ") { w ->
                val tag = if (w.isMarkedLearning) "⭐" else "🔄"
                "$tag ${w.card.title}"
            }
            if (report.weakItems.size > 18) {
                shown + "\n" + getString(R.string.study_more_hint, report.weakItems.size - 18)
            } else shown
        }

        b.tvRecentTitle.text = getString(R.string.study_recent_title_count, report.recentMastered.size)
        b.tvRecentList.text = if (report.recentMastered.isEmpty()) {
            getString(R.string.study_recent_empty)
        } else {
            val shown = report.recentMastered.take(12).joinToString("\n") { r ->
                val day = dateFmt.format(Date(r.timestampMs))
                "$day  [${r.card.type.label}]  ${r.card.title}"
            }
            if (report.recentMastered.size > 12) {
                shown + "\n" + getString(R.string.study_more_hint, report.recentMastered.size - 12)
            } else shown
        }
    }
}
