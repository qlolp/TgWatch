package ru.tgwatch

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/** Explicit requests only; no reply broadcasts or service-start path. */
class AutomationReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CHECK_NOW = AutomationPolicy.ACTION_CHECK_NOW
        const val ACTION_GET_STATE = AutomationPolicy.ACTION_GET_STATE
        const val EXTRA_TOKEN = "token"
        const val API_VERSION = 1
        const val RESULT_OK = 0
        const val RESULT_UNAUTHORIZED = 1
        const val RESULT_INVALID = 2
        const val RESULT_STOPPED = 3
        const val RESULT_RATE_LIMITED = 4
    }

    // Early security/lifecycle guards keep denied requests from reaching any operation.
    @Suppress("ReturnCount")
    override fun onReceive(context: Context, intent: Intent) {
        if (isOrderedBroadcast) {
            setResultExtras(null)
            resultData = null
            resultCode = RESULT_UNAUTHORIZED
        }
        try {
            synchronized(AutomationSettings) {
                val supplied = intent.getStringExtra(EXTRA_TOKEN)
                if (!AutomationPolicy.authorized(AutomationSettings.enabled(context),
                        AutomationSettings.token(context), supplied)) return
                if (intent.component != ComponentName(context, AutomationReceiver::class.java) ||
                    !AutomationPolicy.validAction(intent.action)) {
                    reply(RESULT_INVALID)
                    return
                }
                if (intent.action == ACTION_CHECK_NOW) {
                    if (!Prefs.isEnabled(context) || !MonitorService.running || BackupManager.hasPending(context)) {
                        reply(RESULT_STOPPED)
                        return
                    }
                    val elapsed = SystemClock.elapsedRealtime()
                    if (!AutomationPolicy.checkAllowed(elapsed, AutomationSettings.lastCheckElapsed(context))) {
                        reply(RESULT_RATE_LIMITED)
                        return
                    }
                    // Spend the throttle only for a check that was accepted: a stopped or
                    // pending service must not eat the next caller attempt.
                    if (!MonitorService.requestRunningCheck(context)) {
                        reply(RESULT_STOPPED)
                        return
                    }
                    if (!AutomationSettings.recordCheck(context, elapsed)) {
                        reply(RESULT_STOPPED)
                        return
                    }
                }
                reply(RESULT_OK, stateResult(context))
            }
        } catch (_: Exception) {
            // Malformed parcelables and inaccessible credential storage disclose no state.
            reply(RESULT_INVALID)
        }
    }

    private fun reply(code: Int, extras: Bundle? = null) {
        if (!isOrderedBroadcast) return
        setResult(code, null, extras)
    }

    private fun stateResult(ctx: Context): Bundle {
        val running = MonitorService.running && Prefs.isEnabled(ctx) && !BackupManager.hasPending(ctx)
        val state = if (running) MonitorService.state else Prefs.loadLastState(ctx) ?: MonitorService.State()
        val checkedAt = state.checkedAt.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val stale = if (running && state.checkedElapsed > 0L) {
            checkedAt <= 0L || state.isStale(now)
        } else {
            AutomationPolicy.stale(running, checkedAt, now, state.expectedIntervalSec)
        }
        return Bundle().apply {
            putString("status", state.status.name)
            putLong("checked_at_ms", checkedAt)
            putBoolean("stale", stale)
            putBoolean("running", running)
            putInt("api_version", API_VERSION)
        }
    }
}
