package ru.tgwatch

/** Recompute only when history changes or the minute window advances/rolls back. */
class DailyStatsCache {
    private data class Snapshot(val version: Long, val minute: Long, val stats: TimeStats)
    @Volatile private var snapshot: Snapshot? = null

    /** UI consumers never acquire the refresh lock or load history. */
    fun peek(): TimeStats? = snapshot?.stats

    fun needsRefresh(historyVersion: Long, now: Long): Boolean {
        val current = snapshot ?: return true
        return current.version != historyVersion || current.minute != now / MINUTE_MS
    }

    /** Only background callers supply a loader; the last complete snapshot remains readable. */
    @Synchronized fun get(historyVersion: Long, now: Long, load: () -> TimeStats): TimeStats {
        val currentMinute = now / MINUTE_MS
        val current = snapshot
        if (current != null && !needsRefresh(historyVersion, now)) return current.stats
        return load().also { snapshot = Snapshot(historyVersion, currentMinute, it) }
    }

    companion object { private const val MINUTE_MS = 60_000L }
}
