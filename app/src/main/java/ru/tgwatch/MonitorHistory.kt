package ru.tgwatch

import android.content.Context

internal data class HistoryPublication(val state: MonitorService.State, val changed: Boolean, val partial: Boolean,
    val outcomes: List<ProbeOutcome> = emptyList(), val dcGeneration: String? = null)

/** All persistence belonging to a measured publication runs on the ordered journal worker. */
internal object MonitorHistory {
    fun publish(ctx: Context, session: Long, publication: HistoryPublication,
        recovery: (RecoveryEvent) -> Unit, refreshed: () -> Unit): Boolean =
        HistorySessions.queue(ctx).submit(session) {
            TargetHistories.markTelegram(session)
            val state = publication.state
            LastSuccessStore.record(ctx, state.status.name, state.checkedAt)
            val kind = when (state.status) {
                MonitorService.Status.OK -> History.Kind.OK
                MonitorService.Status.NO_NETWORK -> History.Kind.OFFLINE
                MonitorService.Status.TG_DOWN -> History.Kind.FAIL
                MonitorService.Status.PARTIAL -> History.Kind.PARTIAL
                MonitorService.Status.UNKNOWN -> History.Kind.UNKNOWN
            }
            History.record(ctx, state.checkedAt, kind, state.latencyMs, state.expectedIntervalSec)
            // Rebuildable metrics can fail independently of the availability journal.
            DcLatencyRecorder.record(ctx, publication.outcomes, state.checkedAt, publication.dcGeneration)
            val event = if (state.status == MonitorService.Status.OK)
                History.recovery(ctx, state.checkedAt, publication.partial) else null
            event?.let { runCatching { recovery(it) } }
            if (publication.changed) EventLog.add(ctx, transitionMessage(ctx, state, event))
            refreshed()
        }

    private fun transitionMessage(ctx: Context, state: MonitorService.State, event: RecoveryEvent?): String {
        val duration = event?.let { ctx.renderString(
            R.string.copy_monitor_history_observed_outage,
            recoveryDurationStr(ctx, it.durationMs)) }.orEmpty()
        return when (state.status) {
            MonitorService.Status.OK -> ctx.renderString(
                R.string.copy_monitor_history_mtproto_and_web_are,
                state.latencyMs,
                duration)
            MonitorService.Status.PARTIAL -> ctx.renderString(
                R.string.copy_monitor_history_telegram_is_partially_reachable,
                state.reason)
            MonitorService.Status.TG_DOWN -> ctx.renderString(
                R.string.copy_monitor_history_telegram_is_unreachable,
                state.reason)
            MonitorService.Status.NO_NETWORK -> ctx.renderString(
                R.string.copy_monitor_history_no_internet,
                state.reason)
            MonitorService.Status.UNKNOWN -> ctx.renderString(R.string.copy_monitor_history_status_unknown)
        }
    }
}
