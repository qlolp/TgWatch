package ru.tgwatch

import java.util.TimeZone

/** Timestamp of the last published OK, independent of current diagnostic state. */
object LastSuccessPolicy {
    fun updated(previous: Long, statusName: String, checkedAt: Long): Long =
        if (statusName == "OK" && checkedAt > 0L) checkedAt else previous.coerceAtLeast(0L)

    /** A future timestamp is evidence of changed wall time, not a fresh success. */
    fun ageMs(lastSuccessAt: Long, now: Long): Long? =
        if (lastSuccessAt <= 0L || now < lastSuccessAt) null else now - lastSuccessAt

    fun label(lastSuccessAt: Long, now: Long, timeZone: TimeZone = TimeZone.getDefault()): TextValue {
        if (lastSuccessAt <= 0L) return TextValue(TextKey.LAST_SUCCESS_EMPTY)
        return textOf(if (ageMs(lastSuccessAt, now) == null) TextKey.LAST_SUCCESS_CLOCK_CHANGED else TextKey.LAST_SUCCESS,
            TextArgument.Number(lastSuccessAt), TextArgument.Text(timeZone.id))
    }
}
