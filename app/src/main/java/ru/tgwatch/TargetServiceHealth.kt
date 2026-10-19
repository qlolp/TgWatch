package ru.tgwatch

import android.content.Context

/** Public cached preferences only: private/parked goals still retain their intended scheduling interval. */
internal object TargetServiceHealth {
    fun expectedIntervalSec(ctx: Context): Int = TargetServiceCommands.expectedIntervalSec(
        MonitorService.state.expectedIntervalSec,
        TargetId.entries.map { TargetPreferences.storedDefinition(ctx, it) }, MonitorService.secondaryIntervals)
}
