package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs

/** Wall-clock anchors let an armed public-response watch survive a reboot without outliving its 24 hours. */
internal object RegionalWatchClock {
    private fun key(service: RegionalService, suffix: String) = RegionalWatchPreferences.key(service, suffix)

    /** Process start and clock changes queue this off the main thread, after recovery opens storage. */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        RecoveryCoordinator.whenReady {
            RegionalRepository.transaction(app, RegionalRepository.generation(app)) { reconcile(app) }
                .whenComplete { resumed, _ ->
                    if (resumed == true) Handler(Looper.getMainLooper()).post { MonitorService.requestRegionalCheck() }
                }
        }
    }

    /**
     * Same boot: keep the wall anchors on the elapsed deadline, so a manual clock change cannot stretch the
     * 24-hour request across a later reboot. New boot: resume only the remaining time of an enabled profile.
     * Returns whether a request was resumed.
     */
    fun reconcile(ctx: Context, now: Long = SystemClock.elapsedRealtime(),
        wallNow: Long = System.currentTimeMillis(),
        currentBoot: Int = RegionalWatchPreferences.boot(ctx)): Boolean {
        return synchronized(RegionalWatchPreferences) { reconcileLocked(ctx, now, wallNow, currentBoot) }
    }

    private fun reconcileLocked(ctx: Context, now: Long, wallNow: Long, currentBoot: Int): Boolean {
        val ready = !BackupManager.hasPending() && RecoveryCoordinator.state == RecoveryState.READY
        if (!ready || currentBoot < 0) return false
        val prefs = Prefs.sp(ctx)
        val clock = Clock(now, wallNow, currentBoot)
        return RegionalService.entries.filter { prefs.contains(key(it, "armed")) }.map { service ->
            runCatching {
                val sameBoot = prefs.getInt(key(service, "boot"), -1) == currentBoot
                if (sameBoot) reanchor(prefs, service, now, wallNow)
                !sameBoot && resume(ctx, service, clock)
            }.getOrDefault(false)
        }.any { it }
    }

    private class Clock(val elapsed: Long, val wall: Long, val boot: Int)

    /** Also gives a request armed before wall anchors existed the anchors it needs to survive a reboot. */
    private fun reanchor(prefs: SharedPreferences, service: RegionalService, now: Long, wallNow: Long): Boolean {
        val request = RegionalWatchRequest(prefs.getLong(key(service, "armed"), -1),
            prefs.getLong(key(service, "deadline"), -1))
        val deadlineWall = Math.addExact(wallNow, request.deadlineElapsed - now)
        val aligned = prefs.contains(key(service, "armed_wall")) &&
            abs(prefs.getLong(key(service, "deadline_wall"), -1) - deadlineWall) <= CLOCK_SLACK_MS
        return !request.active(now) || aligned || prefs.edit()
            .putLong(key(service, "armed_wall"), deadlineWall - (request.deadlineElapsed - request.armedElapsed))
            .putLong(key(service, "deadline_wall"), deadlineWall).commit()
    }

    /** True only when a request was actually resumed; a clock behind the arming time waits for a sync. */
    private fun resume(ctx: Context, service: RegionalService, clock: Clock): Boolean {
        val prefs = Prefs.sp(ctx)
        val outcome = RegionalWatchRequest.resume(clock.elapsed, prefs.getLong(key(service, "armed_wall"), -1),
            prefs.getLong(key(service, "deadline_wall"), -1), clock.wall)
        val profile = RegionalPreferences.enabled(ctx, service) &&
            prefs.getLong(key(service, "profile"), -1) == RegionalPreferences.profileRevision(ctx, service)
        return when {
            outcome == RegionalWatchResume.Wait -> false
            outcome !is RegionalWatchResume.Resumed || !profile -> {
                RegionalWatchPreferences.clear(prefs, service)
                false
            }
            else -> prefs.edit().putLong(key(service, "armed"), outcome.request.armedElapsed)
                .putLong(key(service, "deadline"), outcome.request.deadlineElapsed)
                .putLong(key(service, "armed_wall"), clock.wall)
                .putInt(key(service, "boot"), clock.boot)
                .putBoolean(key(service, "restored"), true).commit()
        }
    }

    private const val CLOCK_SLACK_MS = 2_000L
}
