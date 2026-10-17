package ru.tgwatch

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

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

        private val stateDispatcher = AutomationStateDispatcher()
        internal fun computeHealth(): AutomationComputeHealth = stateDispatcher.health()
        internal fun invalidateState() = stateDispatcher.invalidate()

    }

    // Early security/lifecycle guards keep denied requests from reaching any operation.
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Catch is a best-effort boundary that logs the class only.
    override fun onReceive(context: Context, intent: Intent) {
        if (isOrderedBroadcast) {
            setResultExtras(null)
            resultData = null
            resultCode = RESULT_UNAUTHORIZED
        }
        try {
            val app = context.applicationContext
            var deliverSnapshot = intent.action == ACTION_GET_STATE
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
                    if (!Prefs.isEnabled(context) || !MonitorService.running || BackupManager.hasPending()) {
                        reply(RESULT_STOPPED)
                        return
                    }
                    val elapsed = SystemClock.elapsedRealtime()
                    when (AutomationCheckAdmission.request(elapsed, AutomationSettings.lastCheckElapsed(context),
                        { admission -> MonitorService.requestRunningCheck(context, admission) },
                        { AutomationSettings.recordCheck(context, elapsed) })) {
                        AutomationCheckAdmission.Result.ACCEPTED -> deliverSnapshot = isOrderedBroadcast
                        AutomationCheckAdmission.Result.RATE_LIMITED -> {
                            reply(RESULT_RATE_LIMITED)
                            return
                        }
                        AutomationCheckAdmission.Result.UNAVAILABLE -> {
                            reply(RESULT_STOPPED)
                            return
                        }
                    }
                }
            }
            if (deliverSnapshot) {
                deliverState(app)
            } else {
                reply(RESULT_OK)
            }
        } catch (e: Exception) {
            // Malformed parcelables and inaccessible credential storage disclose no state.
            Log.w("TgWatch", "automation request: ${e.javaClass.simpleName}")
            reply(RESULT_INVALID)
        }
    }

    private fun deliverState(ctx: Context) {
        val elapsed = SystemClock.elapsedRealtime()
        val cached = AutomationStateCache.take(elapsed)
        when {
            cached != null -> reply(RESULT_OK, cached.toBundle())
            !isOrderedBroadcast -> reply(RESULT_OK)
            !AutomationStateCache.beginCompute(elapsed) -> {
                val raced = AutomationStateCache.take(elapsed)
                if (raced != null) reply(RESULT_OK, raced.toBundle()) else reply(RESULT_RATE_LIMITED)
            }
            else -> scheduleStateCompute(ctx)
        }
    }

    private fun scheduleStateCompute(ctx: Context) {
        val pending = goAsync()
        stateDispatcher.submit({ stateSnapshot(ctx) }, { result ->
            val snapshot = result.getOrNull()
            val error = result.exceptionOrNull()
            val code = when {
                snapshot != null -> RESULT_OK
                error is RejectedExecutionException || error is TimeoutException -> RESULT_RATE_LIMITED
                else -> RESULT_INVALID
            }
            pending.setResult(code, null, snapshot?.toBundle())
        }, { pending.finish() })
    }

    private fun reply(code: Int, extras: Bundle? = null) {
        if (!isOrderedBroadcast) return
        setResult(code, null, extras)
    }

    private fun stateSnapshot(ctx: Context): AutomationStateSnapshot {
        val running = MonitorService.running && Prefs.isEnabled(ctx) && !BackupManager.hasPending()
        val state = if (running) MonitorService.state else Prefs.loadLastState(ctx) ?: MonitorService.State()
        val checkedAt = state.checkedAt.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val stale = if (running && state.checkedElapsed > 0L) {
            checkedAt <= 0L || state.isStale(now)
        } else {
            AutomationPolicy.stale(running, checkedAt, now, state.expectedIntervalSec)
        }
        return AutomationStateSnapshot(
            status = state.status.name,
            checkedAtMs = checkedAt,
            stale = stale,
            running = running,
        )
    }
}
