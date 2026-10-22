package ru.tgwatch

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.SystemClock

/** All live public evidence surfaces share the same stop/configuration/network/age boundary. */
internal object RegionalCurrent {
    fun result(ctx: Context, service: RegionalService): RegionalResult? {
        val power = ctx.getSystemService(PowerManager::class.java)
        val interval = if (!power.isInteractive || power.isPowerSaveMode)
            RegionalMonitor.SLEEP_MS else RegionalMonitor.INTERVAL_MS
        val scope = RegionalFreshnessScope(SystemClock.elapsedRealtime(),
            ctx.getSystemService(ConnectivityManager::class.java).activeNetwork?.networkHandle,
            RegionalPreferences.revision(ctx),
            Prefs.isEnabled(ctx) && MonitorService.running && !BackupManager.hasPending(),
            RegionalPreferences.enabled(ctx, service), interval * 2)
        return RegionalStateStore.get(service)?.takeIf { RegionalFreshness.current(it, scope) }
    }
}
