package ru.tgwatch

/** Owns revision validation and replacement of the pending check. */
class CheckSchedule(private val resetBackoff: () -> Unit, private val replace: (Long) -> Unit) {
    @Volatile var revision = 0L
        private set

    @Synchronized fun request(delayMs: Long) { replace(delayMs) }

    @Synchronized fun networkChanged(delayMs: Long) {
        revision++
        resetBackoff()
        replace(delayMs)
    }

    @Synchronized fun afterCheck(checkedRevision: Long, normalDelayMs: Long) {
        replace(if (checkedRevision == revision) normalDelayMs else 1_000L)
    }
}
