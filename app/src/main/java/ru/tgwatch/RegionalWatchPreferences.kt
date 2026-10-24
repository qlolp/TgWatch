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
        now: Long = SystemClock.elapsedRealtime(), wallNow: Long = System.currentTimeMillis(),
        currentBoot: Int = boot(ctx)): RegionalWatchMetadata? {
        if (BackupManager.hasPending() || RecoveryCoordinator.state != RecoveryState.READY) return null
        return runCatching {
            val prefs = Prefs.sp(ctx)
            if (!prefs.contains(key(service, "armed")) || currentBoot < 0) null else {
                val request = RegionalWatchRequest(prefs.getLong(key(service, "armed"), -1),
                    prefs.getLong(key(service, "deadline"), -1))
                val profile = prefs.getLong(key(service, "profile"), -1)
                val storedBoot = prefs.getInt(key(service, "boot"), -1)
                val metadata = if (storedBoot == currentBoot) {
                    RegionalWatchMetadata(request, storedBoot, profile,
                        prefs.getBoolean(key(service, "restored"), false))
                } else {
                    val rebased = request.afterReboot(now, prefs.getLong(key(service, "armed_wall"), -1),
                        prefs.getLong(key(service, "deadline_wall"), -1), wallNow)
                    if (rebased == null || !RegionalPreferences.enabled(ctx, service) ||
                        profile != RegionalPreferences.profileRevision(ctx, service)) {
                        clear(prefs, service)
                        null
                    } else {
                        val saved = prefs.edit().putLong(key(service, "armed"), rebased.armedElapsed)
                            .putLong(key(service, "deadline"), rebased.deadlineElapsed)
                            .putLong(key(service, "armed_wall"), wallNow)
                            .putInt(key(service, "boot"), currentBoot)
                            .putBoolean(key(service, "restored"), true).commit()
                        if (saved) RegionalWatchMetadata(rebased, currentBoot, profile, true) else null
                    }
                }
                metadata?.takeIf {
                    it.current(currentBoot, RegionalPreferences.profileRevision(ctx, service),
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
            val wallNow = System.currentTimeMillis() -
                (SystemClock.elapsedRealtime() - now).coerceAtLeast(0L)
            Prefs.sp(ctx).edit().putLong(key(service, "armed"), now)
                .putLong(key(service, "deadline"), Math.addExact(now, RegionalWatchRequest.TTL_MS))
                .putLong(key(service, "armed_wall"), wallNow)
                .putLong(key(service, "deadline_wall"), Math.addExact(wallNow, RegionalWatchRequest.TTL_MS))
                .putInt(key(service, "boot"), boot(ctx))
                .putLong(key(service, "profile"), RegionalPreferences.profileRevision(ctx, service))
                .putBoolean(key(service, "restored"), false).commit()
        }.getOrDefault(false)
    }

    @Synchronized fun cancel(ctx: Context, service: RegionalService,
        expected: RegionalWatchMetadata? = null): Boolean {
        if (expected != null && current(ctx, service) != expected) return false
        return clear(Prefs.sp(ctx), service)
    }

    private fun clear(prefs: android.content.SharedPreferences, service: RegionalService): Boolean {
        val editor = prefs.edit()
        listOf("armed", "deadline", "armed_wall", "deadline_wall", "boot", "profile", "restored")
            .forEach { editor.remove(key(service, it)) }
        return editor.commit()
    }

    @Synchronized fun consume(ctx: Context, service: RegionalService, expected: RegionalWatchMetadata): Boolean =
        cancel(ctx, service, expected)

    /** Keep an old callback from canceling or replacing a card belonging to an explicit newer request. */
    @Synchronized fun ifCurrent(ctx: Context, service: RegionalService, expected: RegionalWatchMetadata,
        action: () -> Boolean): Boolean = current(ctx, service) == expected && action()
}
