package ru.tgwatch

/** Only immutable public measurement fields enter event delivery, using the measurement's real age. */
internal object TargetWebhookObservation {
    fun from(state: TargetSnapshot, report: TargetReport, networkRevision: Long,
        wallNow: Long, elapsedNow: Long): WebhookObservation {
        val stale = if (state.checkedElapsedMs > 0L)
            ProbeRules.isStale(state.checkedElapsedMs, elapsedNow, state.expectedIntervalSec)
            else ProbeRules.isStale(state.checkedAtMs, wallNow, state.expectedIntervalSec)
        return WebhookObservation(state.id.wire, state.status.name, state.checkedAtMs, state.latencyMs,
            report.networkKind, networkRevision, report.publishable, stale, state.expectedIntervalSec)
    }
}
