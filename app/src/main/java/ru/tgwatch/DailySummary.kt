package ru.tgwatch

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Text and timing of the once-a-day summary notification; no Android dependencies. */
object DailySummary {
    const val DEFAULT_MINUTE = 21 * 60

    data class Text(val title: TextValue, val body: TextValue)

    /** null when the day has no checks; language is resolved only by the caller's Context. */
    fun text(s: TimeStats, id: TargetId = TargetId.TELEGRAM): Text? {
        if (s.checks == 0) return null
        val name = TextArgument.Value(TargetNames.text(id))
        val title = if (s.uptimePercent >= 0) textOf(TextKey.DAILY_TITLE_PERCENT,
            name, TextArgument.Decimal(s.uptimePercent)) else textOf(TextKey.DAILY_TITLE, name)
        val body = buildList {
            add(when {
                s.outageCount == 0 -> textOf(TextKey.DAILY_NO_OUTAGES, name)
                s.lastOutageOngoing -> textOf(TextKey.DAILY_ONGOING, name, TextArgument.Number(s.outageCount.toLong()),
                    TextArgument.Duration(DurationParts.from(s.lastOutageMs), precise = true))
                else -> textOf(TextKey.DAILY_LONGEST, name, TextArgument.Number(s.outageCount.toLong()),
                    TextArgument.Duration(DurationParts.from(s.longestOutageMs)))
            })
            if (s.partialMs >= MINUTE_MS) add(textOf(TextKey.DAILY_PARTIAL,
                TextArgument.Duration(DurationParts.from(s.partialMs))))
            if (s.offlineCount > 0) add(textOf(TextKey.DAILY_OFFLINE,
                TextArgument.Duration(DurationParts.from(s.offlineMs))))
            if (s.avgLatencyMs >= 0) add(textOf(TextKey.DAILY_LATENCY, TextArgument.Number(s.avgLatencyMs)))
            if (s.unknownMs >= MINUTE_MS) add(textOf(TextKey.DAILY_UNOBSERVED,
                TextArgument.Duration(DurationParts.from(s.unknownMs))))
            add(textOf(TextKey.DAILY_CHECKS, TextArgument.Number(s.checks.toLong())))
        }
        return Text(title, textLines(body))
    }

    fun targets(values: Map<TargetId, TimeStats?>): Text? {
        if (values.isEmpty() || values.values.all { it != null && it.checks == 0 }) return null
        val blocks = values.map { (id, stats) ->
            val name = TextArgument.Value(TargetNames.text(id))
            val value = stats?.let { text(it, id) }
            when {
                stats == null -> textOf(TextKey.DAILY_UNAVAILABLE, name)
                value == null -> textOf(TextKey.DAILY_NO_CHECKS, name)
                else -> textLines(listOf(value.title, value.body))
            }
        }
        return if (values.keys == setOf(TargetId.TELEGRAM) && values[TargetId.TELEGRAM] != null)
            text(checkNotNull(values[TargetId.TELEGRAM]))
            else Text(TextValue(TextKey.DAILY_ALL_TARGETS), TextValue(TextKey.BLOCKS, blocks.map(TextArgument::Value)))
    }

    private const val MINUTE_MS = 60_000L

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
