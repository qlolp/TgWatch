package ru.tgwatch

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock

/** Independent measurements and display cooldowns for the secondary goals. */
internal class TargetSlowAlerts(private val ctx: Context, private val manager: NotificationManager,
    private val events: (TargetId) -> EventNotifications) {
    private class Entry(val threshold: Long) {
        val facts = TargetSlowAlert { TargetAlertSettings(slowThresholdMs = threshold) }
        val display = SlowAlertPresentation()
        var latency = -1L
    }
    private val entries = mutableMapOf<TargetId, Entry>()

    fun onCheck(state: TargetSnapshot, definition: TargetDefinition, live: Boolean) {
        val entry = entry(definition)
        entry.facts.onCheck(state, true, SystemClock.elapsedRealtime())
        if (state.status == TargetStatus.OK && state.latencyMs != null) entry.latency = state.latencyMs
        reconcile(state, definition, live)
    }

    fun reconcile(state: TargetSnapshot, definition: TargetDefinition, live: Boolean) {
        val entry = entries[state.id] ?: return
        if (entry.threshold != definition.alerts.slowThresholdMs) { forget(state.id); return }
        val now = System.currentTimeMillis()
        val blocked = manager.getNotificationChannel(EventNotifications.SLOW_CHANNEL)?.importance ==
            NotificationManager.IMPORTANCE_NONE
        val enabled = definition.alerts.notifySlow && manager.areNotificationsEnabled() && !blocked
        val muted = Prefs.alertsMutedUntil(ctx, now, state.id) > now
        val quiet = !Prefs.alertsAllowed(ctx, now, state.id) && !muted
        val fresh = if (state.checkedElapsedMs > 0L)
            !ProbeRules.isStale(state.checkedElapsedMs, SystemClock.elapsedRealtime(), state.expectedIntervalSec)
            else !ProbeRules.isStale(state.checkedAtMs, now, state.expectedIntervalSec)
        val display = SlowDisplayState(entry.facts.active, enabled, muted, quiet,
            live && fresh && state.status == TargetStatus.OK)
        when (entry.display.reconcile(display, SystemClock.elapsedRealtime())) {
            SlowDisplayAction.SHOW -> events(state.id).slow(entry.latency)
            SlowDisplayAction.CLEAR -> manager.cancel(TargetEventNotifications.id(state.id, "slow"))
            SlowDisplayAction.NONE -> Unit
        }
    }

    fun forget(id: TargetId) {
        entries.remove(id)
        manager.cancel(TargetEventNotifications.id(id, "slow"))
    }
    fun unmeasured(id: TargetId) {
        entries[id]?.facts?.onCheck(TargetSnapshot(id), false, SystemClock.elapsedRealtime())
    }

    private fun entry(definition: TargetDefinition): Entry {
        val previous = entries[definition.id]
        if (previous != null && previous.threshold != definition.alerts.slowThresholdMs) forget(definition.id)
        return entries.getOrPut(definition.id) { Entry(definition.alerts.slowThresholdMs) }
    }
}
