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
            var request = TargetAutomationRequest(TargetId.TELEGRAM, 1, false)
            var deliverSnapshot = true
            synchronized(AutomationSettings) {
                val supplied = intent.getStringExtra(EXTRA_TOKEN)
                if (!AutomationPolicy.authorized(AutomationSettings.enabled(context),
                        AutomationSettings.token(context), supplied)) return
                if (intent.component != ComponentName(context, AutomationReceiver::class.java) ||
                    !AutomationPolicy.validAction(intent.action)) {
                    reply(RESULT_INVALID)
                    return
                }
                val parsed = TargetAutomation.parse(intent.action,
                    intent.getStringExtra(TargetAutomation.EXTRA_TARGET_ID))
                if (parsed == null) { reply(RESULT_INVALID); return }
                request = parsed
                if (request.checkNow) {
                    val admitted = admitCheck(context, request.targetId)
                    if (admitted != RESULT_OK) { reply(admitted); return }
                    deliverSnapshot = isOrderedBroadcast
                }
            }
            if (deliverSnapshot) {
                deliverState(app, request)
            } else {
                reply(RESULT_OK)
            }
        } catch (e: Exception) {
            // Malformed parcelables and inaccessible credential storage disclose no state.
            Log.w("TgWatch", "automation request: ${e.javaClass.simpleName}")
            reply(RESULT_INVALID)
        }
    }

    private fun admitCheck(ctx: Context, id: TargetId): Int {
        if (!TargetPreferences.definition(ctx, id).enabled || !MonitorService.running || BackupManager.hasPending())
            return RESULT_STOPPED
        val elapsed = SystemClock.elapsedRealtime()
        return when (AutomationCheckAdmission.request(elapsed, AutomationSettings.lastCheckElapsed(ctx, id),
            { admission -> MonitorService.requestRunningCheck(ctx, id, admission) },
            { AutomationSettings.recordCheck(ctx, elapsed, id) })) {
            AutomationCheckAdmission.Result.ACCEPTED -> RESULT_OK
            AutomationCheckAdmission.Result.RATE_LIMITED -> RESULT_RATE_LIMITED
            AutomationCheckAdmission.Result.UNAVAILABLE -> RESULT_STOPPED
        }
    }

    private fun deliverState(ctx: Context, request: TargetAutomationRequest) {
        val elapsed = SystemClock.elapsedRealtime()
        val key = AutomationStateKey(request.targetId, request.apiVersion)
        val cached = AutomationStateCache.take(elapsed, key)
        when {
            cached != null -> reply(RESULT_OK, cached.toBundle())
            !isOrderedBroadcast -> reply(RESULT_OK)
            !AutomationStateCache.beginCompute(elapsed, key) -> {
                val raced = AutomationStateCache.take(elapsed, key)
                if (raced != null) reply(RESULT_OK, raced.toBundle()) else reply(RESULT_RATE_LIMITED)
            }
            else -> scheduleStateCompute(ctx, key)
        }
    }

    private fun scheduleStateCompute(ctx: Context, key: AutomationStateKey) {
        val pending = goAsync()
        stateDispatcher.submit({ TargetAutomationState.compute(ctx, key) }, { result ->
            val snapshot = result.getOrNull()
            val error = result.exceptionOrNull()
            val code = when {
                snapshot != null -> RESULT_OK
                error is RejectedExecutionException || error is TimeoutException -> RESULT_RATE_LIMITED
                else -> RESULT_INVALID
            }
            pending.setResult(code, null, snapshot?.toBundle())
        }, { pending.finish() }, key)
    }

    private fun reply(code: Int, extras: Bundle? = null) {
        if (!isOrderedBroadcast) return
        setResult(code, null, extras)
    }

}
