package ru.tgwatch

/** Per-goal policy, independent from Android presentation and opt-in switches. */
class TargetSlowAlert(private val settings: () -> TargetAlertSettings) {
    private var threshold = -1L
    private var policy = SlowAlert()
    val active: Boolean get() = synchronized(this) { policy.active }
    @Synchronized fun clear(nowElapsed: Long) = policy.onCheck("STOPPED", -1L, nowElapsed)

    @Synchronized fun refreshSettings() {
        val selected = settings().slowThresholdMs
        if (selected != threshold) {
            threshold = selected
            policy = SlowAlert(selected, clearLevel(selected))
        }
    }

    @Synchronized fun onCheck(snapshot: TargetSnapshot, measured: Boolean, nowElapsed: Long): SlowAlert.Action {
        val previousActive = policy.active
        refreshSettings()
        val action = policy.onCheck(if (measured) snapshot.status.name else "UNKNOWN",
            snapshot.latencyMs ?: -1L, nowElapsed)
        return if (previousActive && !policy.active) SlowAlert.Action.CLEAR else action
    }
    companion object {
        fun clearLevel(thresholdMs: Long) = thresholdMs * CLEAR_NUMERATOR / CLEAR_DENOMINATOR
        private const val CLEAR_NUMERATOR = 3L
        private const val CLEAR_DENOMINATOR = 5L
    }
}
