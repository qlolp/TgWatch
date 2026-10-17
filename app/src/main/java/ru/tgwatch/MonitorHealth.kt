package ru.tgwatch

/** Immutable, local monitoring health; no addresses, exception text or credentials. */
data class MonitorHealth(
    val checkStartedElapsed: Long = 0L,
    val notMeasuredCount: Int = 0,
    val primaryBusy: Int = 0,
    val secondaryBusy: Int = 0,
    val storageFailure: HistoryFailure? = null,
    val recoveryState: RecoveryState = RecoveryState.READY,
)
