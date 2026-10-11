package ru.tgwatch

/** Recompute only when history changes or the minute window advances/rolls back. */
class DailyStatsCache {
    private var version = -1L
    private var minute = -1L
    private var stats: TimeStats? = null

    @Synchronized fun get(historyVersion: Long, now: Long, load: () -> TimeStats): TimeStats {
        val currentMinute = now / MINUTE_MS
        if (stats == null || historyVersion != version || currentMinute != minute) {
            stats = load()
            version = historyVersion
            minute = currentMinute
        }
        return checkNotNull(stats)
    }

    companion object { private const val MINUTE_MS = 60_000L }
}
