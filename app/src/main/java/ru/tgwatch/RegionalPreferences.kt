package ru.tgwatch

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Device-local opt-ins. Restore deliberately clears this namespace instead of importing live state. */
internal object RegionalPreferences {
    const val PREFIX = "regional."
    const val MAINTENANCE = "regional.maintenance"
    private const val REVISION = "regional.revision"
    private const val UNSEEN_BOOT = -2
    fun revision(ctx: Context): Long = Prefs.sp(ctx).getLong(REVISION, 0)
    fun enabled(ctx: Context, service: RegionalService): Boolean =
        Prefs.sp(ctx).getBoolean("$PREFIX${service.wire}.enabled", false)
    fun anyEnabled(ctx: Context): Boolean = RegionalService.entries.any { enabled(ctx, it) }
    fun onlyRegional(ctx: Context): Boolean = anyEnabled(ctx) &&
        TargetId.entries.none { TargetPreferences.definition(ctx, it).enabled }

    @Synchronized fun setEnabled(ctx: Context, service: RegionalService, enabled: Boolean,
        expectedRevision: Long = revision(ctx)): Boolean {
        val allowed = !BackupManager.hasPending() && RecoveryCoordinator.state == RecoveryState.READY &&
            expectedRevision == revision(ctx)
        return allowed && Prefs.sp(ctx).edit().putBoolean("$PREFIX${service.wire}.enabled", enabled)
            .putLong(key(service, "enabled_revision"), profileRevision(ctx, service) + 1)
            .putLong(REVISION, expectedRevision + 1).commit()
    }

    private fun key(service: RegionalService, suffix: String) = "$PREFIX${service.wire}.$suffix"
    private fun profileRevision(ctx: Context, service: RegionalService): Long =
        Prefs.sp(ctx).getLong(key(service, "enabled_revision"), 0)
    fun notificationAllowed(ctx: Context, service: RegionalService, network: Long): Boolean {
        val prefs = Prefs.sp(ctx)
        val sameBoot = boot(ctx) >= 0 && prefs.getInt(key(service, "boot"), UNSEEN_BOOT) == boot(ctx)
        if (!sameBoot) return true
        val since = SystemClock.elapsedRealtime() - prefs.getLong(key(service, "delivered_at"), 0)
        val sameIncident = prefs.getLong(key(service, "delivered_network"), -1) == network &&
            prefs.getLong(key(service, "delivered_revision"), -1) == profileRevision(ctx, service)
        return !sameIncident && since >= RegionalConsensus.COOLDOWN_MS
    }

    fun delivered(ctx: Context, service: RegionalService, network: Long) {
        Prefs.sp(ctx).edit().putInt(key(service, "boot"), boot(ctx))
            .putLong(key(service, "delivered_at"), SystemClock.elapsedRealtime())
            .putLong(key(service, "delivered_network"), network)
            .putLong(key(service, "delivered_revision"), profileRevision(ctx, service)).apply()
    }

    fun reachable(ctx: Context, service: RegionalService) {
        Prefs.sp(ctx).edit().remove(key(service, "delivered_network")).apply()
    }
}

private fun boot(ctx: Context): Int = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
