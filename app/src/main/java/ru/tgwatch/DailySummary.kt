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
        val title = if (s.uptimePercent >= 0) Lang.t("Telegram за сутки: ${formatPercent(s.uptimePercent)}", "Telegram in the last 24 h: ${formatPercent(s.uptimePercent)}")
            else Lang.t("Telegram за сутки", "Telegram in the last 24 h")
        val body = buildList {
            add(when {
                s.outageCount == 0 -> Lang.t("Сбоев Telegram не было", "No Telegram outages")
                s.lastOutageOngoing -> Lang.t("Сбоев Telegram: ${s.outageCount}, сбой продолжается ", "Telegram outages: ${s.outageCount}, ongoing for ") +
                    recoveryDurationStr(s.lastOutageMs)
                else -> Lang.t("Сбоев Telegram: ${s.outageCount}, самый долгий ${durationStr(s.longestOutageMs)}", "Telegram outages: ${s.outageCount}, longest ${durationStr(s.longestOutageMs)}")
            })
            if (s.partialMs >= 60_000L) add(Lang.t("Частично доступен: ${durationStr(s.partialMs)}", "Partially reachable: ${durationStr(s.partialMs)}"))
            if (s.offlineCount > 0) add(Lang.t("Без интернета: ${durationStr(s.offlineMs)}", "No internet: ${durationStr(s.offlineMs)}"))
            if (s.avgLatencyMs >= 0) add(Lang.t("Средний отклик: ${s.avgLatencyMs} мс", "Average response: ${s.avgLatencyMs} ms"))
            if (s.unknownMs >= 60_000L) add(Lang.t("Без наблюдения: ${durationStr(s.unknownMs)}", "Not monitored: ${durationStr(s.unknownMs)}"))
            add(Lang.t("Проверок: ${s.checks}", "Checks: ${s.checks}"))
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
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            // A nonexistent DST-gap time may have normalized today's target
            // to a different hour. Reapply the user's time for tomorrow.
            cal.set(Calendar.HOUR_OF_DAY, minute / 60)
            cal.set(Calendar.MINUTE, minute % 60)
        }
        return cal.timeInMillis
    }

    /** A manual preview always posts; the scheduled one needs opt-in, monitoring on and no delivery today. */
    fun due(force: Boolean, optedIn: Boolean, monitoring: Boolean, deliveredToday: Boolean): Boolean =
        force || (optedIn && monitoring && !deliveredToday)

    fun timeLabel(minuteOfDay: Int): String = String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
}
