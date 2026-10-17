package ru.tgwatch

import java.io.IOException

/** Reads only the requested target; endpoint revisions never merge coverage or incidents. Worker-only. */
class TargetHistory(private val read: (TargetId) -> List<TargetObservation>,
    private val endpointRevision: (TargetId) -> Long) {
    private data class Cached(val days: Int, val endpointRevision: Long, val stats: TimeStats)
    private val lastKnown = mutableMapOf<TargetId, Cached>()

    fun readStats(id: TargetId, days: Int = 1, nowMs: Long = System.currentTimeMillis()): HistoryRead<TimeStats> {
        val window = days.coerceIn(1, MAX_DAYS)
        val selectedRevision = endpointRevision(id)
        return try {
            val rows = currentRows(id)
            if (selectedRevision != endpointRevision(id)) throw HistoryUnavailableException(HistoryFailure.BACKPRESSURE)
            val stats = Timeline.stats(rows, nowMs - window * CoverageStats.DAY_MS, nowMs)
            lastKnown[id] = Cached(window, selectedRevision, stats)
            HistoryRead.Ready(stats)
        } catch (e: HistoryUnavailableException) {
            unavailable(id, window, e.failure)
        } catch (_: IOException) {
            unavailable(id, window, HistoryFailure.READ)
        }
    }

    fun currentRows(id: TargetId): List<Observation> {
        val current = endpointRevision(id)
        return read(id).asSequence().filter { it.endpointRevision == current }.map { it.row }.toList()
    }

    private fun unavailable(id: TargetId, days: Int, failure: HistoryFailure) =
        HistoryRead.Unavailable(lastKnown[id]?.takeIf {
            it.days == days && it.endpointRevision == endpointRevision(id)
        }?.stats, failure)

    private companion object { const val MAX_DAYS = 7 }
}
