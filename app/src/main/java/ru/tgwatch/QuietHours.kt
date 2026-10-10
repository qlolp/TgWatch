package ru.tgwatch

import java.util.Locale

/** Local minute-of-day quiet-hour policy. */
object QuietHours {
    fun contains(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
        val minute = minuteOfDay.coerceIn(0, 1439)
        val start = startMinute.coerceIn(0, 1439)
        val end = endMinute.coerceIn(0, 1439)
        if (start == end) return false
        return if (start < end) minute in start until end else minute >= start || minute < end
    }

    fun resolveMinute(storedMinute: Int?, legacyHour: Int): Int =
        storedMinute?.takeIf { it in 0..1439 } ?: (legacyHour.coerceIn(0, 23) * 60)

    fun label(startMinute: Int, endMinute: Int): String {
        val start = startMinute.coerceIn(0, 1439)
        val end = endMinute.coerceIn(0, 1439)
        return String.format(Locale.ROOT, "%02d:%02d–%02d:%02d", start / 60, start % 60, end / 60, end % 60)
    }
}
