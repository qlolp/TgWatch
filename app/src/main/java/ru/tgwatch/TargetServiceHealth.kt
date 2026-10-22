package ru.tgwatch

import android.content.Context

/** Public cached preferences only: private/parked goals still retain their intended scheduling interval. */
internal object TargetServiceHealth {
    private const val MILLIS_PER_SECOND = 1_000L
    fun expectedIntervalSec(ctx: Context): Int = TargetServiceCommands.expectedIntervalSec(
        MonitorService.state.expectedIntervalSec,
        TargetId.entries.map { TargetPreferences.storedDefinition(ctx, it) }, MonitorService.secondaryIntervals)
        .let { interval ->
            if (RegionalPreferences.onlyRegional(ctx))
                (RegionalMonitor.SLEEP_MS / MILLIS_PER_SECOND).toInt() else interval
        }
}
