package ru.tgwatch

import android.content.Context
import android.os.SystemClock

/** No endpoint text, history, diagnostics or credentials can enter either response schema. */
internal object TargetAutomationState {
    fun compute(ctx: Context, key: AutomationStateKey): AutomationStateSnapshot {
        val running = MonitorService.running && TargetPreferences.definition(ctx, key.targetId).enabled &&
            !BackupManager.hasPending()
        return if (key.apiVersion == 1) legacy(ctx, running) else target(ctx, key.targetId, running)
    }

    private fun legacy(ctx: Context, running: Boolean): AutomationStateSnapshot {
        val state = if (running) MonitorService.state else Prefs.loadLastState(ctx) ?: MonitorService.State()
        val checkedAt = state.checkedAt.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val stale = if (running && state.checkedElapsed > 0L) checkedAt <= 0L || state.isStale(now)
            else AutomationPolicy.stale(running, checkedAt, now, state.expectedIntervalSec)
        return AutomationStateSnapshot(state.status.name, checkedAt, stale, running)
    }

    private fun target(ctx: Context, id: TargetId, running: Boolean): AutomationStateSnapshot {
        val state = TargetStateStore.current(ctx, id)
        val measuredInProcess = state.checkedElapsedMs > 0L
        val stale = !running || if (measuredInProcess)
            ProbeRules.isStale(state.checkedElapsedMs, SystemClock.elapsedRealtime(), state.expectedIntervalSec)
            else ProbeRules.isStale(state.checkedAtMs, System.currentTimeMillis(), state.expectedIntervalSec)
        return AutomationStateSnapshot(state.status.name, state.checkedAtMs, stale, running, 2, id, state.latencyMs)
    }
}
