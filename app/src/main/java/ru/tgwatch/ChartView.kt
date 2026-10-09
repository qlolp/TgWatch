package ru.tgwatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * График за последний час: один столбик на минуту.
 * Высота — среднее время ответа; цвет по большинству проверок в минуте.
 * Нажатие на столбик показывает детали.
 */
class ChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val slots = 60

    private var minutes: List<History.Minute> = emptyList()
    private var nowMinute = 0L

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
        textSize = 11f * dp
    }

    private val rect = RectF()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setData(data: List<History.Minute>, now: Long) {
        minutes = data
        nowMinute = now / 60_000L
        contentDescription = describe()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val w = width.toFloat()
        if (w <= 0f) return true
        val slotW = w / slots
        val i = (event.x / slotW).toInt().coerceIn(0, slots - 1)
        val minute = nowMinute - (slots - 1 - i)
        val m = minutes.firstOrNull { it.minute == minute }
        val whenText = timeFmt.format(Date(minute * 60_000L))
        val msg = if (m == null || m.total == 0) {
            "$whenText — проверок не было"
        } else {
            buildString {
                append(whenText)
                append(": ")
                append("${m.ok} ok")
                if (m.fail > 0) append(", ${m.fail} без Telegram")
                if (m.offline > 0) append(", ${m.offline} без сети")
                if (m.avgLatency >= 0) append(", ср. ${m.avgLatency} мс")
            }
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelH = 16f * dp
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
            val minute = nowMinute - (slots - 1 - i)
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
        canvas.drawText("${scale.toLong()} мс", 0f, top - 4f * dp, textPaint)
        canvas.drawText("60 мин назад", 0f, height - 2f * dp, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        val avg = minutes.mapNotNull { m -> m.avgLatency.takeIf { it >= 0 } }.average().takeIf { !it.isNaN() }
        canvas.drawText(
            if (avg != null) "сейчас · ср. ${avg.toLong()} мс" else "сейчас",
            w, height - 2f * dp, textPaint
        )
    }

    private fun describe(): String {
        val ok = minutes.sumOf { it.ok }
        val fail = minutes.sumOf { it.fail }
        val offline = minutes.sumOf { it.offline }
        return "За последний час: успешных проверок $ok, Telegram недоступен $fail, нет интернета $offline. Нажми столбик, чтобы увидеть детали."
    }
}
