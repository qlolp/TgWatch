package ru.tgwatch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

/** Text and PNG use precisely the same frozen labels. No live state or private source is consulted. */
object StatusCardRenderer {
    private const val WIDTH = 1024
    private const val HEIGHT = 768
    private const val MARGIN = 48f
    private const val TEXT_SIZE = 28f
    private const val TITLE_SIZE = 36f
    private const val LINE_HEIGHT = 38f
    private const val CHART_TOP = 600f
    private const val CHART_HEIGHT = 80f
    private const val MINUTE_MS = 60_000
    private const val PNG_QUALITY = 100
    private const val COLOR_OK = 0xff0878ad.toInt()
    private const val COLOR_DOWN = 0xffd32f2f.toInt()
    private const val COLOR_PARTIAL = 0xffb45300.toInt()
    private const val COLOR_BACKGROUND = 0xfff2f4f7.toInt()
    private const val ROUND_FULL_EDGE = 99.95
    private const val BELOW_FULL = 99.9
    private const val FULL = 100.0
    fun text(snapshot: StatusShareSnapshot): String = labels(snapshot).joinToString("\n")
    fun labels(value: StatusShareSnapshot): List<String> {
        fun t(ru: String, en: String) = if (value.russian) ru else en
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT).apply {
            timeZone = SimpleTimeZone(value.zoneOffsetMinutes * MINUTE_MS, "fixed")
        }
        val latency = value.latencyMs?.takeIf { value.status in setOf(TargetStatus.OK, TargetStatus.PARTIAL) }
            ?.let { "$it " + t("мс", "ms") } ?: "—"
        return listOf("TgWatch · " + name(value),
            t("Последний результат: ", "Last result: ") + status(value.status, value.russian),
            if (value.running) t("Мониторинг включён на момент снимка", "Monitoring enabled at capture time")
                else t("Мониторинг остановлен", "Monitoring stopped"),
            t("Снимок: ", "Captured: ") + format.format(Date(value.capturedAtMs)),
            t("Проверено: ", "Checked: ") + if (value.checkedAtMs > 0) format.format(Date(value.checkedAtMs)) else "—",
            if (value.stale) t("Измерение устарело или не подтверждено", "Measurement is stale or unconfirmed")
                else t("Измерение свежее на момент снимка", "Measurement was fresh at capture time"),
            t("Время ответа: ", "Response time: ") + latency,
            value.coveragePercent?.let { t("Покрытие за час: ", "One-hour coverage: ") + percentage(value, it) }
                ?: t("Нет данных истории для оценки доступности", "No history data for reachability assessment"),
            t("Последний час · пробелы не означают сбои", "Last hour · gaps are not outages"), graphLabel(value))
    }
    private fun name(value: StatusShareSnapshot): String = when (value.targetId) {
        TargetId.TELEGRAM -> "Telegram"
        TargetId.MATRIX -> "Matrix"
        TargetId.WHATSAPP -> if (value.russian) "WhatsApp (веб)" else "WhatsApp (web)"
        TargetId.CUSTOM -> if (value.russian) "Свой сервис" else "Custom service"
    }
    private fun status(status: TargetStatus, russian: Boolean): String {
        fun t(ru: String, en: String) = if (russian) ru else en
        return when (status) {
            TargetStatus.OK -> t("Доступен", "Reachable")
            TargetStatus.PARTIAL -> t("Частично доступен", "Partially reachable")
            TargetStatus.DOWN -> t("Недоступен", "Unreachable")
            TargetStatus.NO_NETWORK -> t("Нет сети", "No network")
            TargetStatus.UNKNOWN -> t("Не определено", "Unknown")
        }
    }
    private fun graphLabel(value: StatusShareSnapshot): String = if (value.chart.isEmpty()) "—" else
        TargetStatus.entries.joinToString(" · ") {
            status(it, value.russian) + ": " + value.chart.count { row -> row == it }
        }
    private fun percentage(value: StatusShareSnapshot, number: Double): String {
        val displayed = if (number >= ROUND_FULL_EDGE && number < FULL) BELOW_FULL else number
        val locale = if (value.russian) Locale("ru") else Locale.US
        return String.format(locale, "%.1f", displayed).removeSuffix(if (value.russian) ",0" else ".0") +
            if (value.russian) " %" else "%"
    }
    fun png(snapshot: StatusShareSnapshot, output: OutputStream) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(COLOR_BACKGROUND)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = TEXT_SIZE }
            paint.color = Color.WHITE
            canvas.drawRoundRect(MARGIN / 2, MARGIN / 2, WIDTH - MARGIN / 2, HEIGHT - MARGIN / 2,
                MARGIN / 2, MARGIN / 2, paint)
            var y = MARGIN * 2
            for ((index, label) in labels(snapshot).withIndex()) {
                paint.color = if (index == 0) COLOR_OK else Color.BLACK
                paint.textSize = if (index == 0) TITLE_SIZE else TEXT_SIZE
                var rest = label
                while (rest.isNotEmpty()) {
                    val count = paint.breakText(rest, true, WIDTH - MARGIN * 2, null).coerceAtLeast(1)
                    canvas.drawText(rest.substring(0, count), MARGIN, y, paint)
                    rest = rest.substring(count)
                    y += LINE_HEIGHT
                }
            }
            paint.color = Color.LTGRAY
            canvas.drawRect(MARGIN, CHART_TOP, WIDTH - MARGIN, CHART_TOP + CHART_HEIGHT, paint)
            snapshot.chart.forEachIndexed { index, status ->
                paint.color = when (status) {
                    TargetStatus.OK -> COLOR_OK
                    TargetStatus.DOWN -> COLOR_DOWN
                    TargetStatus.PARTIAL, TargetStatus.NO_NETWORK -> COLOR_PARTIAL
                    TargetStatus.UNKNOWN -> Color.LTGRAY
                }
                val width = (WIDTH - MARGIN * 2) / snapshot.chart.size
                canvas.drawRect(MARGIN + index * width, CHART_TOP, MARGIN + (index + 1) * width,
                    CHART_TOP + CHART_HEIGHT, paint)
            }
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output))
                throw IOException("Cannot encode status card")
        } finally { bitmap.recycle() }
    }
}
