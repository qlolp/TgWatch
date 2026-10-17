package ru.tgwatch

import android.content.Context

/** Fixed public fields only; persisted elapsed clocks are deliberately discarded across process recreation. */
internal object TargetStateStore {
    fun current(ctx: Context, id: TargetId): TargetSnapshot {
        val current = MonitorService.targetRepository?.snapshot(id)
        val revision = TargetPreferences.endpointRevision(ctx, id)
        if (current != null && current.endpointRevision == revision) return current
        return if (id == TargetId.TELEGRAM) TargetSnapshot.fromTelegram(
            Prefs.loadLastState(ctx) ?: MonitorService.State()).copy(endpointRevision = revision)
            else load(ctx, id)
    }

    fun persist(ctx: Context, state: TargetSnapshot) {
        fun key(field: String) = TargetPreferences.key(state.id, "last_$field")
        check(Prefs.sp(ctx).edit().putString(key("status"), state.status.name)
            .putLong(key("checked"), state.checkedAtMs).putLong(key("latency"), state.latencyMs ?: -1L)
            .putInt(key("interval"), state.expectedIntervalSec)
            .putLong(key("endpoint"), state.endpointRevision).commit())
    }

    fun load(ctx: Context, id: TargetId): TargetSnapshot {
        val revision = TargetPreferences.endpointRevision(ctx, id)
        val defaults = TargetSnapshot(id, endpointRevision = revision)
        fun key(field: String) = TargetPreferences.key(id, "last_$field")
        val prefs = Prefs.sp(ctx)
        if (prefs.getLong(key("endpoint"), 0) != revision) return defaults
        return defaults.copy(status = TargetStatus.fromWire(prefs.getString(key("status"), "UNKNOWN").orEmpty())
            ?: TargetStatus.UNKNOWN, checkedAtMs = prefs.getLong(key("checked"), 0).coerceAtLeast(0),
            latencyMs = prefs.getLong(key("latency"), -1).takeIf { it >= 0 },
            expectedIntervalSec = prefs.getInt(key("interval"), defaults.expectedIntervalSec)
                .coerceIn(TargetDefinition.MIN_TELEGRAM_SEC, TargetDefinition.MAX_TARGET_SEC))
    }
}
