package ru.tgwatch

import android.content.Context
import android.os.SystemClock
import android.os.UserManager

internal data class WidgetSnapshot(val widgetId: Int, val target: TargetId, val token: String?,
    val state: TargetSnapshot, val running: Boolean, val locked: Boolean, val fresh: Boolean,
    val lastSuccessAt: Long, val aggregate: WidgetAggregate?) {
    companion object {
        fun fresh(state: TargetSnapshot, running: Boolean, locked: Boolean, nowMs: Long, nowElapsed: Long): Boolean =
            running && !locked && state.checkedElapsedMs > 0 && nowMs >= state.checkedAtMs &&
                !ProbeRules.isStale(state.checkedElapsedMs, nowElapsed, state.expectedIntervalSec)

        /** Cached public state only. It performs no history read or network work. */
        fun load(ctx: Context, appWidgetId: Int): WidgetSnapshot {
            val id = WidgetPreferences.target(ctx, appWidgetId)
            val state = TargetStateStore.current(ctx, id)
            val definition = TargetPreferences.definition(ctx, id)
            val locked = definition.requiresEndpoint &&
                (!ctx.getSystemService(UserManager::class.java).isUserUnlocked ||
                    Prefs.sp(ctx).getBoolean(TargetPreferences.key(id, "endpoint_review"), false))
            val running = definition.enabled && MonitorService.running
            return WidgetSnapshot(appWidgetId, id, WidgetPreferences.token(ctx, appWidgetId), state, running, locked,
                fresh(state, running, locked, System.currentTimeMillis(), SystemClock.elapsedRealtime()),
                LastSuccessStore.lastSuccessAt(ctx, id), WidgetHistoryCache.cached(ctx, id))
        }
    }
}
