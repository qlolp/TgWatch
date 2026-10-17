package ru.tgwatch

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Only immutable, accepted, fresh goal observations reach this event presenter. */
internal class TargetAlertController(private val ctx: Context, private val manager: NotificationManager,
    private val active: () -> Boolean) {
    private val latest = mutableMapOf<TargetId, TargetSnapshot>()
    private val notifications = mutableMapOf<TargetId, EventNotifications>()
    private val slow = TargetSlowAlerts(ctx, manager, ::events)
    private val handler = Handler(Looper.getMainLooper())
    private val queued = AtomicBoolean()
    @Volatile private var closed = false
    private val reconcileTask = Runnable { queued.set(false); reconcile() }
    private val settings = Prefs.sp(ctx)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val target = key?.startsWith("target.") == true && !key.contains(".last_")
        val common = key == null || key == "enabled" || key.startsWith("quiet_") || key.startsWith("alert_mute_") ||
            key == "alerts_muted_until"
        val relevant = target || common
        if (relevant && !closed && queued.compareAndSet(false, true)) handler.post(reconcileTask)
    }
    init { settings.registerOnSharedPreferenceChangeListener(listener) }

    @Synchronized fun onCheck(state: TargetSnapshot) {
        val eligible = state.id != TargetId.TELEGRAM
        if (closed || !eligible || stale(state)) return
        val definition = TargetPreferences.definition(ctx, state.id)
        if (!active() || !definition.enabled) { forget(state.id); return }
        val previous = latest.put(state.id, state)
        val event = events(state.id)
        when (state.status) {
            TargetStatus.DOWN, TargetStatus.PARTIAL -> outage(state, previous, definition)
            TargetStatus.OK -> event.clearOutage()
            TargetStatus.NO_NETWORK -> { event.clearOutage(); event.clearRecovery() }
            TargetStatus.UNKNOWN -> Unit
        }
        slow.onCheck(state, definition, active())
    }
    @Synchronized fun unmeasured(id: TargetId) { if (!closed) slow.unmeasured(id) }

    private fun outage(state: TargetSnapshot, previous: TargetSnapshot?, definition: TargetDefinition) {
        if (previous?.status != state.status && definition.alerts.notifyOutage) {
            val allowed = Prefs.alertsAllowed(ctx, id = state.id)
            val sound = Prefs.soundFor(ctx, if (state.status == TargetStatus.DOWN) "TG_DOWN" else "PARTIAL")
            if (state.status == TargetStatus.DOWN) events(state.id).outage(help(state.id), sound, allowed)
            else events(state.id).partial(help(state.id), sound, allowed)
        }
    }

    @Synchronized fun recovered(state: TargetSnapshot, recovery: RecoveryEvent) {
        val definition = TargetPreferences.definition(ctx, state.id)
        val current = !closed && active() && definition.enabled && latest[state.id] === state
        if (current && definition.alerts.notifyRecovery && !stale(state))
            events(state.id).recovered(recovery, Prefs.soundFor(ctx, "RECOVERY"),
                Prefs.alertsAllowed(ctx, id = state.id))
    }

    @Synchronized fun reconcile() {
        if (closed) return
        for (id in TargetId.entries) {
            val raw = TargetPreferences.storedDefinition(ctx, id)
            val state = latest[id]
            val endpointChanged = state != null && state.endpointRevision != TargetPreferences.endpointRevision(ctx, id)
            val disabled = !Prefs.isEnabled(ctx) || !raw.enabled || TargetConfigTransactions.isPending(id)
            if (disabled || endpointChanged) {
                forget(id)
            } else if (id != TargetId.TELEGRAM) {
                if (!raw.alerts.notifyOutage) notifications[id]?.clearOutage()
                if (!raw.alerts.notifyRecovery) notifications[id]?.clearRecovery()
                state?.let { slow.reconcile(it, raw, active() && !TargetPreferences.fence.snapshot(id).changing) }
            }
        }
    }

    @Synchronized fun forget(id: TargetId) {
        latest.remove(id)
        slow.forget(id)
        manager.cancel(TargetEventNotifications.id(id, "outage"))
        manager.cancel(TargetEventNotifications.id(id, "partial"))
        manager.cancel(TargetEventNotifications.id(id, "recovery"))
    }

    @Synchronized fun close() {
        closed = true
        settings.unregisterOnSharedPreferenceChangeListener(listener)
        handler.removeCallbacks(reconcileTask)
        TargetId.entries.filter { it != TargetId.TELEGRAM }.forEach(::forget)
    }

    private fun stale(state: TargetSnapshot): Boolean = if (state.checkedElapsedMs > 0L)
        ProbeRules.isStale(state.checkedElapsedMs, SystemClock.elapsedRealtime(), state.expectedIntervalSec)
        else ProbeRules.isStale(state.checkedAtMs, System.currentTimeMillis(), state.expectedIntervalSec)
    private fun events(id: TargetId) = notifications.getOrPut(id) { EventNotifications(ctx, manager, targetId = id) }
    private fun help(id: TargetId): String = ctx.getString(when (id) {
        TargetId.MATRIX -> R.string.target_matrix_help
        TargetId.WHATSAPP -> R.string.target_whatsapp_help
        else -> R.string.target_custom_help
    })
}
