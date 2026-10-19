package ru.tgwatch

/** Measurement provenance is independent of wall clock, Android lifecycle and endpoint identity. */
data class TargetSnapshot(val id: TargetId, val status: TargetStatus = TargetStatus.UNKNOWN,
    val checkedAtMs: Long = 0L, val checkedElapsedMs: Long = 0L,
    val expectedIntervalSec: Int = TargetDefinition.MIN_TARGET_SEC, val latencyMs: Long? = null,
    val generation: Long = 0L, val revision: Long = 0L, val endpointRevision: Long = 0L,
    val health: MonitorHealth = MonitorHealth(), val failure: ru.tgwatch.probe.ProbeDetailCode? = null) {
    init {
        require(checkedAtMs >= 0L && checkedElapsedMs >= 0L)
        require(expectedIntervalSec in TargetDefinition.MIN_TELEGRAM_SEC..TargetPowerPolicy.MAX_EFFECTIVE_SEC)
        require(latencyMs == null || latencyMs >= 0L)
        require(generation >= 0L && revision >= 0L && endpointRevision >= 0L)
    }

    fun withoutElapsedClock(): TargetSnapshot = copy(checkedElapsedMs = 0L)

    fun toLegacyTelegram(previous: MonitorService.State = MonitorService.State()): MonitorService.State {
        require(id == TargetId.TELEGRAM)
        val legacy = MonitorService.Status.valueOf(if (status == TargetStatus.DOWN) "TG_DOWN" else status.name)
        return previous.copy(status = legacy, checkedAt = checkedAtMs, checkedElapsed = checkedElapsedMs,
            expectedIntervalSec = expectedIntervalSec, latencyMs = latencyMs ?: -1L,
            since = if (previous.status == legacy && previous.checkedAt > 0L) previous.since else checkedAtMs)
    }

    companion object {
        fun fromTelegram(state: MonitorService.State, generation: Long = 0L, revision: Long = 0L,
            health: MonitorHealth = MonitorHealth()) = TargetSnapshot(TargetId.TELEGRAM,
            checkNotNull(TargetStatus.fromWire(state.status.name)), state.checkedAt, state.checkedElapsed,
            state.expectedIntervalSec, state.latencyMs.takeIf { it >= 0L }, generation, revision, 0L, health)
    }
}
