package ru.tgwatch

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.provider.Settings

/** Only armed intent is durable. No network identity or positive observation is stored here. */
internal object RegionalWatchPreferences {
    private fun key(service: RegionalService, suffix: String) =
        "${RegionalPreferences.PREFIX}watch.${service.wire}.$suffix"
    private fun boot(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    @Synchronized fun current(ctx: Context, service: RegionalService,
        now: Long = SystemClock.elapsedRealtime()): RegionalWatchMetadata? {
        if (BackupManager.hasPending() || RecoveryCoordinator.state != RecoveryState.READY) return null
        return runCatching {
            val prefs = Prefs.sp(ctx)
            if (!prefs.contains(key(service, "armed"))) null else {
                val request = RegionalWatchRequest(prefs.getLong(key(service, "armed"), -1),
                    prefs.getLong(key(service, "deadline"), -1))
                RegionalWatchMetadata(request, prefs.getInt(key(service, "boot"), -1),
                    prefs.getLong(key(service, "profile"), -1)).takeIf {
                    it.current(boot(ctx), RegionalPreferences.profileRevision(ctx, service),
                        RegionalPreferences.enabled(ctx, service), now)
                }
            }
        }.getOrNull()
    }

    /** The UI additionally resolves the live service result before entering its settings transaction. */
    @Synchronized fun arm(ctx: Context, result: RegionalResult,
        now: Long = SystemClock.elapsedRealtime()): Boolean {
        val confirmed = result.status == RegionalStatus.REGION_RESTRICTED && result.regionConfirmed
        val scope = RegionalFreshnessScope(now,
            ctx.getSystemService(ConnectivityManager::class.java).activeNetwork?.networkHandle,
            RegionalPreferences.revision(ctx), Prefs.isEnabled(ctx) && !BackupManager.hasPending(),
            RegionalPreferences.enabled(ctx, result.service), RegionalMonitor.SLEEP_MS * 2)
        val allowed = confirmed && RegionalFreshness.current(result, scope) && boot(ctx) >= 0 &&
            RecoveryCoordinator.state == RecoveryState.READY
        return allowed && runCatching {
            val service = result.service
            Prefs.sp(ctx).edit().putLong(key(service, "armed"), now)
                .putLong(key(service, "deadline"), Math.addExact(now, RegionalWatchRequest.TTL_MS))
                .putInt(key(service, "boot"), boot(ctx))
                .putLong(key(service, "profile"), RegionalPreferences.profileRevision(ctx, service)).commit()
        }.getOrDefault(false)
    }

    @Synchronized fun cancel(ctx: Context, service: RegionalService,
        expected: RegionalWatchMetadata? = null): Boolean {
        if (expected != null && current(ctx, service) != expected) return false
        val editor = Prefs.sp(ctx).edit()
        listOf("armed", "deadline", "boot", "profile").forEach { editor.remove(key(service, it)) }
        return editor.commit()
    }

    @Synchronized fun consume(ctx: Context, service: RegionalService, expected: RegionalWatchMetadata): Boolean =
        cancel(ctx, service, expected)
}
