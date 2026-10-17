package ru.tgwatch

import android.content.Context
import java.io.IOException

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
private const val WEEK_MS = 604_800_000L
enum class LatencyWindow(val durationMs: Long) {
    HOUR(HOUR_MS), DAY(DAY_MS), WEEK(WEEK_MS)
}

/** One immutable cohort shared by the text, plot and accessibility description. */
data class LatencyDisplay(val summary: LatencySummary, val samples: List<Observation>, val errorCount: Int,
    val missingCount: Int, val clockEpoch: Long, val endpointRevision: Long,
    val fromMs: Long, val untilMs: Long, val coverage: CoverageStats,
    val version: Long = 0L, val generation: String? = null) {
    companion object {
        fun reduce(rows: List<TargetObservation>, revision: Long, fromMs: Long, untilMs: Long): LatencyDisplay {
            val own = rows.filter { it.endpointRevision == revision }.map { it.row }
            val checks = Timeline.currentChecks(own).filter { it.at in fromMs..untilMs }
            val samples = checks.filter { it.kind in setOf("OK", "PARTIAL") && it.latencyMs >= 0L }
                .sortedBy { it.at }
            val stats = Timeline.stats(own, fromMs, untilMs)
            return LatencyDisplay(LatencySummary.exact(samples.map { it.latencyMs }), samples,
                checks.count { it.kind !in setOf("OK", "PARTIAL") },
                checks.count { it.kind in setOf("OK", "PARTIAL") && it.latencyMs < 0L },
                own.maxOfOrNull { it.clockEpoch } ?: 0L, revision, fromMs, untilMs,
                CoverageStats.from(stats, untilMs - fromMs))
        }

        /** Worker-only, under the same history monitor as endpoint/generation replacement. */
        fun load(ctx: Context, id: TargetId, window: LatencyWindow,
            nowMs: Long = System.currentTimeMillis()): HistoryRead<LatencyDisplay> = synchronized(History) {
            try {
                val revision = TargetPreferences.endpointRevision(ctx, id)
                val rows = TargetHistories.rows(ctx, id, nowMs).map { TargetObservation(it, revision) }
                HistoryRead.Ready(reduce(rows, revision, (nowMs - window.durationMs).coerceAtLeast(0L), nowMs)
                    .copy(version = TargetHistoryPresentation.version(id),
                        generation = TargetPersistence.active?.generationId))
            } catch (e: HistoryUnavailableException) {
                HistoryRead.Unavailable(null, e.failure)
            } catch (_: IOException) {
                HistoryRead.Unavailable(null, HistoryFailure.READ)
            }
        }
    }
}
