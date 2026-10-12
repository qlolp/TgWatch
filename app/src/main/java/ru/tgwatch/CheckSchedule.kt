package ru.tgwatch

/**
 * Owns revision validation and replacement of the pending check.
 * Requests that arrive while a check runs coalesce into exactly one follow-up check:
 * completion never replaces them with the normal interval.
 */
class CheckSchedule(private val resetBackoff: () -> Unit, private val replace: (Long) -> Unit) {
    @Volatile var revision = 0L
        private set
    // Smallest delay requested since the running check started; null when none arrived.
    private var requestedDuringCheck: Long? = null

    /** Marks the start of a check and returns the revision its result must still match. */
    @Synchronized fun begin(): Long {
        requestedDuringCheck = null
        return revision
    }

    @Synchronized fun request(delayMs: Long) {
        requestedDuringCheck = minOf(requestedDuringCheck ?: delayMs, delayMs)
        replace(delayMs)
    }

    /** A wake-up for the pending timer (alarm) is satisfied by a check that is already running. */
    @Synchronized fun wake() { replace(0L) }

    @Synchronized fun networkChanged(delayMs: Long) {
        revision++
        resetBackoff()
        replace(delayMs)
    }

    @Synchronized fun afterCheck(checkedRevision: Long, normalDelayMs: Long) {
        val next = if (checkedRevision == revision) normalDelayMs else 1_000L
        replace(requestedDuringCheck?.let { minOf(it, next) } ?: next)
        requestedDuringCheck = null
    }
}
