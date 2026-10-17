package ru.tgwatch

import android.app.Activity
import android.os.SystemClock
import android.widget.TextView

/** Rendering consumes counters only, so a slow disk cannot block the UI ticker. */
internal class MonitorHealthView(private val activity: Activity) {
    private val view = activity.findViewById<TextView>(R.id.tvMonitorHealth)

    fun refresh(now: Long) {
        val health = MonitorService.healthSnapshot()
        val state = MonitorService.state
        val stale = !MonitorService.running || state.checkedAt <= 0L || state.isStale(now)
        val elapsed = SystemClock.elapsedRealtime()
        val current = if (health.checkStartedElapsed > 0L)
            durationStr((elapsed - health.checkStartedElapsed).coerceAtLeast(0))
            else activity.getString(R.string.health_idle)
        view.text = listOfNotNull(
            activity.getString(R.string.health_completed, agoStr(state.checkedAt, now),
                activity.getString(if (stale) R.string.health_stale else R.string.health_fresh)),
            activity.getString(R.string.health_check, current),
            activity.getString(R.string.health_slots, health.notMeasuredCount,
                health.primaryBusy, health.secondaryBusy),
            health.storageFailure?.let { activity.getString(R.string.health_storage_failed, it.name) }
                ?: activity.getString(R.string.health_storage_ok),
            when (health.recoveryState) {
                RecoveryState.READY -> null
                RecoveryState.INITIALIZING -> activity.getString(R.string.health_recovering)
                RecoveryState.FAILED -> activity.getString(R.string.health_recovery_failed)
            },
        ).joinToString("\n")
    }
}
