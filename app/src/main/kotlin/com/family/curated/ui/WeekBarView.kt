package com.family.curated.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.family.curated.R
import com.family.curated.data.ReportMath

/**
 * 周报的条形图（OPT-08）。
 *
 * 特意不引图表库：电视的内存和这个 View 的需求量级差得太远，一个
 * MPAndroidChart 进来光方法数就上千，而这里只要「7 根柱子 + 一条上限虚线」。
 *
 * 每根柱子两段：下段是小剧场自己的时长（黄色），上段是第三方应用那段（蓝色）。
 * 纵轴最大值由 [ReportMath.scaleMax] 给出，至少覆盖当天上限 —— 这样「今天用满了没有」
 * 不用看数字，一眼就能看出来。
 */
class WeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val rows = ArrayList<ReportMath.DayRow>()
    private var scaleMax = 0L
    private var limitMs = 0L

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp
        color = context.getColor(R.color.lock_gray)
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)
    }

    private val ownColor = context.getColor(R.color.accent)
    private val extColor = context.getColor(R.color.ext_blue)
    private val trackColor = context.getColor(R.color.bg_card_focus)
    private val primaryColor = context.getColor(R.color.text_primary)
    private val secondaryColor = context.getColor(R.color.text_secondary)
    private val dimColor = context.getColor(R.color.text_dim)

    private val barRect = RectF()
    private val clipRect = RectF()
    private val limitPath = Path()

    private val topPad = 26 * dp
    private val labelH = 40 * dp

    fun setData(data: List<ReportMath.DayRow>, scaleMaxMs: Long, limit: Long) {
        rows.clear()
        rows.addAll(data)
        scaleMax = scaleMaxMs.coerceAtLeast(0L)
        limitMs = limit.coerceAtLeast(0L)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val chartTop = topPad
        val chartBottom = h - labelH
        val chartH = (chartBottom - chartTop).coerceAtLeast(1f)
        val colW = w / ReportMath.WEEK_DAYS

        // 上限虚线：画在柱子后面，避免盖住数字
        if (limitMs > 0L && scaleMax > 0L) {
            val y = chartBottom - chartH * ReportMath.ratio(limitMs, scaleMax)
            limitPath.reset()
            limitPath.moveTo(0f, y)
            limitPath.lineTo(w, y)
            canvas.drawPath(limitPath, limitPaint)
        }

        if (rows.isEmpty()) {
            labelPaint.textSize = 15 * dp
            labelPaint.color = dimColor
            canvas.drawText("这周还没有记录", w / 2f, (chartTop + chartBottom) / 2f, labelPaint)
            return
        }

        rows.forEachIndexed { i, row ->
            val cx = colW * (i + 0.5f)
            val barW = colW * 0.46f

            // 底色轨道：只给「还没到的日子」，让整周看起来是完整的 7 天
            if (row.future) {
                trackPaint.color = trackColor
                barRect.set(cx - barW / 2f, chartTop, cx + barW / 2f, chartBottom)
                canvas.drawRoundRect(barRect, 6 * dp, 6 * dp, trackPaint)
            }

            val totalH = chartH * ReportMath.ratio(row.totalMs, scaleMax)
            if (totalH > 0.5f) {
                val ownH = chartH * ReportMath.ratio(row.ownMs, scaleMax)
                val top = chartBottom - totalH
                // 整段先按「小剧场自己的时长」画，再把第三方应用那段叠在顶部
                barPaint.color = ownColor
                barRect.set(cx - barW / 2f, chartBottom - ownH, cx + barW / 2f, chartBottom)
                canvas.drawRoundRect(barRect, 6 * dp, 6 * dp, barPaint)

                val extH = totalH - ownH
                if (extH > 0.5f) {
                    barPaint.color = extColor
                    barRect.set(cx - barW / 2f, top, cx + barW / 2f, chartBottom - ownH)
                    canvas.save()
                    clipRect.set(cx - barW / 2f, top, cx + barW / 2f, chartBottom)
                    canvas.clipRect(clipRect)
                    canvas.drawRoundRect(barRect, 6 * dp, 6 * dp, barPaint)
                    canvas.restore()
                }

                // 柱顶数字：分钟数，0 不画
                val minutes = (row.totalMs / 60_000L).toInt()
                if (minutes > 0) {
                    textPaint.textSize = 13 * dp
                    textPaint.color = if (row.isToday) primaryColor else secondaryColor
                    canvas.drawText(minutes.toString(), cx, top - 7 * dp, textPaint)
                }
            }

            // 今天给一根描边，滚动时也能一眼找到
            if (row.isToday) {
                trackPaint.color = ownColor
                trackPaint.style = Paint.Style.STROKE
                trackPaint.strokeWidth = 1.5f * dp
                barRect.set(
                    cx - barW / 2f, chartTop, cx + barW / 2f, chartBottom
                )
                canvas.drawRoundRect(barRect, 6 * dp, 6 * dp, trackPaint)
                trackPaint.style = Paint.Style.FILL
            }

            // 星期 + 日期
            labelPaint.textSize = 14 * dp
            labelPaint.color = when {
                row.isToday -> ownColor
                row.future -> dimColor
                else -> secondaryColor
            }
            canvas.drawText(row.weekday, cx, chartBottom + 15 * dp, labelPaint)

            labelPaint.textSize = 12 * dp
            labelPaint.color = if (row.isToday) ownColor else dimColor
            canvas.drawText(row.dayNum.toString(), cx, chartBottom + 32 * dp, labelPaint)

            // 盖章的日子在日期上加一个小圆点（只用图形，不占字宽）
            if (row.done) {
                barPaint.color = ownColor
                canvas.drawCircle(cx + 12 * dp, chartBottom + 28 * dp, 2.2f * dp, barPaint)
            }
        }
    }
}
