package ru.tgwatch

/** Session-local monotonic arrival observations, not an attribution of delay to Doze. */
class ScheduleDiagnostics {
    data class Snapshot(
        val scheduledArrivals: Long = 0,
        val lateArrivals: Long = 0,
        val totalDelayMs: Long = 0,
        val maxDelayMs: Long = 0,
        val idleObservedArrivals: Long = 0,
        val manualRequests: Long = 0,
        val overlappingRequests: Long = 0,
    )
    private var dueElapsed: Long? = null
    private var counters = Snapshot()

    @Synchronized fun scheduled(nowElapsed: Long, delayMs: Long) {
        dueElapsed = nowElapsed + delayMs.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE - nowElapsed)
    }
    @Synchronized fun canceled() { dueElapsed = null }
    @Synchronized fun manual(overlapping: Boolean) {
        dueElapsed = null
        counters = counters.copy(manualRequests = increment(counters.manualRequests),
            overlappingRequests = if (overlapping) increment(counters.overlappingRequests)
                else counters.overlappingRequests)
    }
    @Synchronized fun arrived(nowElapsed: Long, idleObserved: Boolean, overlapping: Boolean = false) {
        if (overlapping) {
            dueElapsed = null
            counters = counters.copy(overlappingRequests = increment(counters.overlappingRequests))
        } else recordScheduledArrival(nowElapsed, idleObserved)
    }
    private fun recordScheduledArrival(nowElapsed: Long, idleObserved: Boolean) {
        val due = dueElapsed ?: return
        if (nowElapsed < due) return // An old alarm must not consume a later timer.
        dueElapsed = null
        val delay = nowElapsed - due
        val late = delay > LATE_TOLERANCE_MS
        counters = counters.copy(scheduledArrivals = increment(counters.scheduledArrivals),
            lateArrivals = if (late) increment(counters.lateArrivals) else counters.lateArrivals,
            totalDelayMs = if (late) counters.totalDelayMs + delay.coerceAtMost(Long.MAX_VALUE - counters.totalDelayMs)
                else counters.totalDelayMs,
            maxDelayMs = if (late) maxOf(counters.maxDelayMs, delay) else counters.maxDelayMs,
            idleObservedArrivals = if (idleObserved) increment(counters.idleObservedArrivals)
                else counters.idleObservedArrivals)
    }
    @Synchronized fun snapshot(): Snapshot = counters

    private fun increment(value: Long) = if (value == Long.MAX_VALUE) value else value + 1
    companion object { const val LATE_TOLERANCE_MS = 1_000L }
}
