package ru.tgwatch

/**
 * Planned downtime windows. During an active window the monitor keeps checking and
 * the status card stays live, but history rows and alerts are skipped so the gap
 * is unobserved time ([CoverageStats] / `unmonitoredMinutes`) instead of an incident.
 */
data class MaintenanceWindow(
    val id: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val daysMask: Int = 0,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotEmpty() && id.length <= MAX_ID_CHARS) { "Invalid maintenance id" }
        require(startMinuteOfDay in 0..MAX_MINUTE && endMinuteOfDay in 0..MAX_END_MINUTE)
        require(daysMask in 0..MAX_DAYS_MASK)
    }

    /** Half-open [start, end); equal endpoints disable the window. End may be before start (overnight). */
    fun coversMinuteOfDay(minuteOfDay: Int): Boolean {
        if (!enabled || startMinuteOfDay == endMinuteOfDay) return false
        return if (startMinuteOfDay < endMinuteOfDay) minuteOfDay in startMinuteOfDay until endMinuteOfDay
        else minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
    }

    fun coversDay(dayOfWeek: Int): Boolean {
        if (daysMask == 0) return true
        require(dayOfWeek in 0..LAST_WEEKDAY)
        return (daysMask shr dayOfWeek) and 1 == 1
    }

    companion object {
        const val MAX_ID_CHARS = 32
        const val MAX_MINUTE = 1439
        /** Exclusive midnight so a window can cover a full local day. */
        const val MAX_END_MINUTE = 1440
        const val MAX_DAYS_MASK = 0b1111111
        const val MAX_WINDOWS = 8
        const val LAST_WEEKDAY = 6
    }
}

/**
 * Pure schedule: local minute-of-week is `dayOfWeek * 1440 + minuteOfDay` with
 * `dayOfWeek` in 0..6 (Monday=0) in the user's current time zone.
 */
object MaintenanceSchedule {
    fun active(windows: List<MaintenanceWindow>, localMinuteOfWeek: Int): Boolean {
        if (windows.isEmpty()) return false
        require(localMinuteOfWeek in 0 until MINUTES_PER_WEEK)
        val day = localMinuteOfWeek / MINUTES_PER_DAY
        val minuteOfDay = localMinuteOfWeek % MINUTES_PER_DAY
        return windows.any { it.enabled && it.coversDay(day) && it.coversMinuteOfDay(minuteOfDay) }
    }

    fun activeAt(windows: List<MaintenanceWindow>, epochMillis: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Boolean {
        val calendar = java.util.Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
        val day = (calendar.get(java.util.Calendar.DAY_OF_WEEK) + WEEKDAY_OFFSET) % DAYS_PER_WEEK
        val minute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR +
            calendar.get(java.util.Calendar.MINUTE)
        return active(windows, day * MINUTES_PER_DAY + minute)
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    private const val MINUTES_PER_WEEK = MINUTES_PER_DAY * 7
    private const val DAYS_PER_WEEK = 7
    private const val WEEKDAY_OFFSET = 5 // Calendar.SUNDAY=1 → Monday=0
}
