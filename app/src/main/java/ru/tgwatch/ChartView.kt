package ru.tgwatch

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.ceil

/**
 * График за последний час: один столбик на минуту.
 * Высота — среднее время ответа; цвет по большинству проверок в минуте.
 * Нажатие на столбик показывает детали.
 */
class ChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private companion object {
        const val LABEL_PADDING_DP = 8f
        /** Bars keep this much height; with 11 sp labels at font scale 1 the view stays at its 120 dp minimum. */
        const val MIN_PLOT_DP = 76f
    }

    private val slots = 60

    private var minutes: List<History.Minute> = emptyList()
    private var nowMinute = 0L
    private var bucketMinutes = 1
    private var windowMinutes = 60

    private val dp = resources.displayMetrics.density

    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.status_ok) }
    private val failPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.status_fail) }
    private val offlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.status_offline) }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.chart_empty) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.divider)
        strokeWidth = 1f * dp
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    private val rect = RectF()
    private var touchedSlot: Int? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // Label rows follow the user's font scale (sp); the view grows with them instead of squeezing the bars.
    private fun labelHeight() = textPaint.fontSpacing + LABEL_PADDING_DP * dp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = ChartLayout.height(labelHeight(), MIN_PLOT_DP * dp, suggestedMinimumHeight)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(wanted, heightMeasureSpec))
    }

    fun setData(data: List<History.Minute>, now: Long, window: Int = 60) {
        windowMinutes = window
        bucketMinutes = (window / slots).coerceAtLeast(1)
        nowMinute = now / 60_000L
        val first = nowMinute - window + 1
        minutes = data.groupBy { first + ((it.minute - first) / bucketMinutes) * bucketMinutes }.map { (key, values) ->
            History.Minute.merge(key, values)
        }
        contentDescription = describe()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (width <= 0) return true
                touchedSlot = (event.x / (width.toFloat() / slots)).toInt().coerceIn(0, slots - 1)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> touchedSlot = null
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        val selected = touchedSlot
        touchedSlot = null
        if (selected != null) {
            Toast.makeText(context, bucketDetails(selected), Toast.LENGTH_LONG).show()
        } else {
            // One accessible entry per bucket also makes every result available to keyboard users.
            AlertDialog.Builder(context).setTitle(R.string.chart_period_details)
                .setItems(Array(slots) { bucketDetails(it) }) { _, _ -> }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        return true
    }

    private fun bucketDetails(slot: Int): String {
        val minute = nowMinute - windowMinutes + 1 + slot * bucketMinutes
        val bucket = minutes.firstOrNull { it.minute == minute }
        val format = if (windowMinutes > 60) "dd.MM HH:mm" else "HH:mm"
        val timestamp = SimpleDateFormat(format, resources.configuration.locales[0]).format(Date(minute * 60_000L))
        if (bucket == null || bucket.total == 0) return context.getString(R.string.chart_bucket_empty, timestamp)
        return "$timestamp: " + context.getString(R.string.chart_bucket_counts,
            bucket.ok, bucket.fail, bucket.offline, bucket.partial, bucket.unknown) +
            if (bucket.avgLatency >= 0) context.getString(R.string.chart_bucket_average, bucket.avgLatency) else ""
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelH = labelHeight()
        val top = labelH
        val bottom = height - labelH
        val chartH = bottom - top
        val w = width.toFloat()
        if (chartH <= 0f || w <= 0f) return

        val byMinute = minutes.associateBy { it.minute }
        val maxLatency = minutes.maxOfOrNull { it.avgLatency } ?: -1L
        val scale = maxOf(300L, (maxLatency * 1.15).toLong()).toFloat()

        canvas.drawLine(0f, bottom, w, bottom, gridPaint)
        canvas.drawLine(0f, top + chartH / 2f, w, top + chartH / 2f, gridPaint)

        val slotW = w / slots
        val gap = maxOf(1f, slotW * 0.25f)
        val radius = minOf(3f * dp, (slotW - gap) / 2f)
        for (i in 0 until slots) {
            val minute = nowMinute - windowMinutes + 1 + i * bucketMinutes
            val m = byMinute[minute]
            val left = i * slotW + gap / 2f
            val right = (i + 1) * slotW - gap / 2f
            if (m == null || m.total == 0) {
                rect.set(left, bottom - 2f * dp, right, bottom)
                canvas.drawRoundRect(rect, radius, radius, emptyPaint)
                continue
            }
            val paint: Paint
            val h: Float
            when {
                m.isUnknownDominant -> {
                    paint = emptyPaint
                    h = 4f * dp
                }
                m.isPartialDominant -> {
                    paint = offlinePaint
                    h = chartH * 0.5f
                }
                m.isFailDominant -> {
                    paint = failPaint
                    h = chartH
                }
                m.isOfflineDominant -> {
                    paint = offlinePaint
                    h = chartH
                }
                else -> {
                    paint = okPaint
                    h = maxOf(3f * dp, chartH * (m.avgLatency.coerceAtLeast(0) / scale).coerceIn(0f, 1f))
                }
            }
            rect.set(left, bottom - h, right, bottom)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.chart_scale, scale.toLong()), 0f, top - 4f * dp, textPaint)
        val beginning = context.getString(if (windowMinutes > 60) R.string.chart_week_ago else R.string.chart_hour_ago)
        val avg = History.Minute.merge(0L, minutes).avgLatency
        val end = if (avg >= 0) context.getString(R.string.chart_now_average, avg)
            else context.getString(R.string.chart_now)
        // Large fonts have deeper descenders; keep them inside the view.
        val baseline = height - maxOf(2f * dp, textPaint.descent())
        val labelGap = LABEL_PADDING_DP * dp
        if (ChartLayout.bothLabelsFit(textPaint.measureText(beginning), textPaint.measureText(end), labelGap, w)) {
            canvas.drawText(beginning, 0f, baseline, textPaint)
        }
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(end, w, baseline, textPaint)
    }

    private fun describe(): String {
        val total = History.Minute.merge(0L, minutes)
        val counts = if (total.total == 0) context.getString(R.string.chart_no_checks) else
            context.getString(R.string.chart_counts, total.ok, total.fail, total.offline, total.partial, total.unknown)
        val average = if (total.avgLatency >= 0) {
            " " + context.getString(R.string.chart_average, total.avgLatency)
        } else ""
        return counts + average + " " + context.getString(R.string.chart_accessibility_hint)
    }
}

/** Text-dependent chart geometry, kept pure so the font-scale rules are testable on the JVM. */
internal object ChartLayout {
    /** Two label rows plus at least [minPlot] for the bars, never below the layout's minimum height. */
    fun height(labelHeight: Float, minPlot: Float, minHeight: Int): Int =
        maxOf(minHeight, ceil(2f * labelHeight + minPlot).toInt())

    /** False when the left "60 min ago" label would run into the right-aligned one; it is then dropped. */
    fun bothLabelsFit(left: Float, right: Float, gap: Float, width: Float): Boolean = left + gap + right <= width
}
