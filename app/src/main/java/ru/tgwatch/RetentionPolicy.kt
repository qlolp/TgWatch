package ru.tgwatch

/**
 * Process-wide retention window. [Prefs.historyRetention] remains the durable source;
 * this holder lets pure workers (journals, display clamps) honor the same window
 * without threading Context through every call.
 */
object RetentionPolicy {
    @Volatile private var current: Retention = Retention.WEEK

    fun update(retention: Retention) {
        current = retention
    }

    fun current(): Retention = current

    fun totalMs(): Long = current.totalMs

    /** Upper bound for stats/CSV/incident windows in calendar days. */
    fun maxDays(): Int = current.days

    fun keepFrom(now: Long): Long = now - totalMs()
}
