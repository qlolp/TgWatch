package ru.tgwatch

import java.util.concurrent.TimeUnit

data class TargetAttempt(val id: TargetId, val generation: Long, val networkRevision: Long,
    val endpointRevision: Long, val startedElapsed: Long, val expectedIntervalSec: Int? = null) {
    init { require(expectedIntervalSec == null ||
        expectedIntervalSec in TargetDefinition.MIN_TELEGRAM_SEC..TargetPowerPolicy.MAX_EFFECTIVE_SEC) }
}
data class TargetReport(val status: TargetStatus, val outcomes: List<ProbeOutcome>,
    val publishable: Boolean, val latencyMs: Long?, val networkKind: NetworkKind = NetworkKind.UNKNOWN,
    val checkedAtMs: Long = 0L, val checkedElapsedMs: Long = 0L,
    val failure: ru.tgwatch.probe.ProbeDetailCode? = null)

/** Primary publication never waits for secondary targets; all secondary work shares one physical lane. */
class TargetRunner(private val primary: () -> ProbeReport,
    private val secondary: (List<ProbeEndpoint>, Long) -> List<ProbeOutcome>,
    private val clockNanos: () -> Long = System::nanoTime) {
    fun check(attempt: TargetAttempt, definition: TargetDefinition, endpoint: String?,
        condition: ru.tgwatch.probe.HttpProbeCondition? = null): TargetReport {
        require(attempt.id == definition.id)
        if (definition.id == TargetId.TELEGRAM) {
            val report = primary()
            return TargetReport(checkNotNull(TargetStatus.fromWire(report.status)), report.results,
                report.publishable, report.latencyMs.takeIf { it >= 0L })
        }
        return checkSecondary(definition, endpoint, condition)
    }

    private fun checkSecondary(definition: TargetDefinition, endpoint: String?,
        condition: ru.tgwatch.probe.HttpProbeCondition?): TargetReport {
        val resolved = TargetEndpoints.resolve(definition, endpoint, condition)
        val started = clockNanos()
        val target = resolved?.let { secondary(listOf(it), ExtraProbes.BATCH_TIMEOUT_MS) }.orEmpty()
        val measured = target.isNotEmpty() && target.none { it.measurement == ProbeMeasurement.NOT_MEASURED }
        val reachable = target.firstOrNull { it.reachable }
        val remaining = (BUDGET_MS - TimeUnit.NANOSECONDS.toMillis(clockNanos() - started)).coerceAtLeast(0)
        val controls = if (measured && reachable == null && remaining > 0L)
            secondary(TargetEndpoints.controls, remaining) else emptyList()
        val confirmedNetwork = controls.any { it.reachable }
        val status = when {
            reachable != null -> TargetStatus.OK
            confirmedNetwork -> TargetStatus.DOWN
            else -> TargetStatus.UNKNOWN
        }
        val completeControls = controls.isNotEmpty() &&
            controls.none { it.measurement == ProbeMeasurement.NOT_MEASURED }
        return TargetReport(status, target + controls,
            measured && (reachable != null || confirmedNetwork || completeControls),
            reachable?.latencyMs?.takeIf { it >= 0L },
            failure = target.firstOrNull { !it.reachable }?.detailCode)
    }
    companion object { const val BUDGET_MS = 8_000L }
}
