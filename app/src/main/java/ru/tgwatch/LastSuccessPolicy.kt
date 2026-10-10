package ru.tgwatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Timestamp of the last published OK, independent of current diagnostic state. */
object LastSuccessPolicy {
    fun updated(previous: Long, statusName: String, checkedAt: Long): Long =
        if (statusName == "OK" && checkedAt > 0L) checkedAt else previous.coerceAtLeast(0L)

    /** A future timestamp is evidence of changed wall time, not a fresh success. */
    fun ageMs(lastSuccessAt: Long, now: Long): Long? =
        if (lastSuccessAt <= 0L || now < lastSuccessAt) null else now - lastSuccessAt

    fun label(lastSuccessAt: Long, now: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (lastSuccessAt <= 0L) return "Последний OK: —"
        val timestamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).apply {
            this.timeZone = timeZone
        }.format(Date(lastSuccessAt))
        return if (ageMs(lastSuccessAt, now) == null) "Часы изменились · OK $timestamp"
            else "Последний OK: $timestamp"
    }
}
