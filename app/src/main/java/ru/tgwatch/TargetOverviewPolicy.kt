package ru.tgwatch

/** Presentation state for one target only; the overview never aggregates availability across services. */
internal enum class TargetOverviewState { OFF, PENDING, CHECKING, STALE, MEASURED }

internal data class TargetOverviewInput(val snapshot: TargetSnapshot, val running: Boolean,
    val enabled: Boolean, val pending: Boolean, val nowMs: Long, val elapsedMs: Long)

internal object TargetOverviewPolicy {
    fun state(input: TargetOverviewInput): TargetOverviewState = when {
        input.pending -> TargetOverviewState.PENDING
        !input.running || !input.enabled -> TargetOverviewState.OFF
        input.snapshot.checkedAtMs == 0L -> TargetOverviewState.CHECKING
        stale(input) -> TargetOverviewState.STALE
        else -> TargetOverviewState.MEASURED
    }

    private fun stale(input: TargetOverviewInput): Boolean {
        val snapshot = input.snapshot
        return if (snapshot.checkedElapsedMs > 0L)
            ProbeRules.isStale(snapshot.checkedElapsedMs, input.elapsedMs, snapshot.expectedIntervalSec)
        else ProbeRules.isStale(snapshot.checkedAtMs, input.nowMs, snapshot.expectedIntervalSec)
    }
}
