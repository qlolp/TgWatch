package ru.tgwatch

import android.content.Context

internal data class HistoryPublication(val state: MonitorService.State, val changed: Boolean, val partial: Boolean)

/** All persistence belonging to a measured publication runs on the ordered journal worker. */
internal object MonitorHistory {
    fun publish(ctx: Context, session: Long, publication: HistoryPublication,
        recovery: (RecoveryEvent) -> Unit, refreshed: () -> Unit): Boolean =
        HistorySessions.queue(ctx).submit(session) {
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
            val event = if (state.status == MonitorService.Status.OK)
                History.recovery(ctx, state.checkedAt, publication.partial) else null
            event?.let { runCatching { recovery(it) } }
            if (publication.changed) EventLog.add(ctx, transitionMessage(state, event))
            refreshed()
        }

    private fun transitionMessage(state: MonitorService.State, event: RecoveryEvent?): String {
        val duration = event?.let { Lang.t(" (наблюдаемый сбой ${recoveryDurationStr(it.durationMs)})",
            " (observed outage ${recoveryDurationStr(it.durationMs)})") }.orEmpty()
        return when (state.status) {
            MonitorService.Status.OK -> Lang.t("MTProto и веб отвечают, ${state.latencyMs} мс$duration",
                "MTProto and web are responding, ${state.latencyMs} ms$duration")
            MonitorService.Status.PARTIAL -> Lang.t("Telegram частично доступен: ${state.reason}",
                "Telegram is partially reachable: ${state.reason}")
            MonitorService.Status.TG_DOWN -> Lang.t("Telegram НЕДОСТУПЕН: ${state.reason}",
                "Telegram is UNREACHABLE: ${state.reason}")
            MonitorService.Status.NO_NETWORK -> Lang.t("Нет интернета: ${state.reason}", "No internet: ${state.reason}")
            MonitorService.Status.UNKNOWN -> Lang.t("Статус неизвестен", "Status unknown")
        }
    }
}
