package ru.tgwatch

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Text and timing of the once-a-day summary notification; no Android dependencies. */
object DailySummary {
    const val DEFAULT_MINUTE = 21 * 60

    data class Text(val title: String, val body: String)

    /** null when the day has no checks: an empty summary is only noise. */
    fun text(s: TimeStats): Text? {
        if (s.checks == 0) return null
        val title = if (s.uptimePercent >= 0) "Telegram за сутки: ${formatPercent(s.uptimePercent)}"
            else "Telegram за сутки"
        val body = buildList {
            add(when {
                s.outageCount == 0 -> "Сбоев Telegram не было"
                s.lastOutageOngoing -> "Сбоев Telegram: ${s.outageCount}, сбой продолжается " +
                    recoveryDurationStr(s.lastOutageMs)
                else -> "Сбоев Telegram: ${s.outageCount}, самый долгий ${durationStr(s.longestOutageMs)}"
            })
            if (s.partialMs >= 60_000L) add("Частично доступен: ${durationStr(s.partialMs)}")
            if (s.offlineCount > 0) add("Без интернета: ${durationStr(s.offlineMs)}")
            if (s.avgLatencyMs >= 0) add("Средний отклик: ${s.avgLatencyMs} мс")
            if (s.unknownMs >= 60_000L) add("Без наблюдения: ${durationStr(s.unknownMs)}")
            add("Проверок: ${s.checks}")
        }.joinToString("\n")
        return Text(title, body)
    }

    /** Next wall-clock moment at [minuteOfDay] strictly after [now]; DST-safe via Calendar. */
    fun nextAt(now: Long, minuteOfDay: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val minute = minuteOfDay.coerceIn(0, 1439)
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    fun timeLabel(minuteOfDay: Int): String = String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
}
