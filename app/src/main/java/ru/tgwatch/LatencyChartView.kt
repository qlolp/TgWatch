package ru.tgwatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Independent points deliberately do not bridge failed checks or gaps. */
class LatencyChartView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var points: List<Pair<Long, Long>> = emptyList()
    private var from = 0L
    private var until = 1L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun show(values: List<Pair<Long, Long>>, fromMs: Long, untilMs: Long) {
        points = values.filter { it.first in fromMs..untilMs && it.second >= 0L }
        from = fromMs
        until = maxOf(fromMs + 1, untilMs)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val margin = MARGIN_DP * resources.displayMetrics.density
        val plotWidth = (width - margin * 2).coerceAtLeast(1f)
        val plotHeight = (height - margin * 2).coerceAtLeast(1f)
        val max = points.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
        paint.color = context.getColor(R.color.text_secondary)
        paint.strokeWidth = resources.displayMetrics.density
        canvas.drawLine(margin, height - margin, width - margin, height - margin, paint)
        paint.color = context.getColor(R.color.tg_blue)
        // Draw one vertical range per screen column; retain outliers without an unbounded onDraw loop.
        val columns = points.groupBy { ((it.first - from).toDouble() / (until - from) * plotWidth).toInt() }
        for ((column, values) in columns) {
            val low = values.minOf { it.second }.toDouble() / max * plotHeight
            val high = values.maxOf { it.second }.toDouble() / max * plotHeight
            val x = margin + column
            canvas.drawLine(x, (height - margin - low).toFloat(), x, (height - margin - high).toFloat(), paint)
            canvas.drawCircle(x, (height - margin - high).toFloat(), POINT_DP * resources.displayMetrics.density, paint)
        }
    }
    private companion object { const val MARGIN_DP = 12f; const val POINT_DP = 2f }
}
