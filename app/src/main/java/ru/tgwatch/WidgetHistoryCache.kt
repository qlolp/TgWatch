package ru.tgwatch

import android.content.Context

internal data class WidgetAggregate(val version: Long, val endpoint: Long, val generation: String?,
    val minute: Long, val coverage: Double?, val bins: List<TargetStatus>)

/** Prepared once per goal/minute on the widget worker. Rendering only reads the immutable cache. */
internal object WidgetHistoryCache {
    private const val MINUTE_MS = 60_000L
    private const val HOURS_SIX_MS = 21_600_000L
    private const val BIN_MS = 300_000L
    private const val BINS = 72
    @Volatile private var values: Map<TargetId, WidgetAggregate> = emptyMap()
    fun cached(ctx: Context, id: TargetId): WidgetAggregate? {
        val failed = if (id == TargetId.TELEGRAM) History.healthFailure else TargetHistories.failures[id]
        val value = values[id] ?: return null
        return value.takeIf { failed == null && it.endpoint == TargetPreferences.endpointRevision(ctx, id) &&
            it.generation == TargetPersistence.active?.generationId &&
            it.version == TargetHistoryPresentation.version(id) &&
            it.minute == System.currentTimeMillis() / MINUTE_MS && !BackupManager.hasPending() }
    }
    fun prepare(ctx: Context, id: TargetId) {
        if (cached(ctx, id) != null || BackupManager.hasPending()) return
        val value = synchronized(History) {
            val now = System.currentTimeMillis()
            val rows = TargetHistories.rows(ctx, id, now)
            val stats = Timeline.stats(rows, now - CoverageStats.DAY_MS, now)
            val segments = Timeline.segments(rows, now - HOURS_SIX_MS, now)
            var cursor = 0
            val bins = List(BINS) { index ->
                val center = now - HOURS_SIX_MS + index * BIN_MS + BIN_MS / 2
                while (cursor < segments.size && segments[cursor].until <= center) cursor++
                segments.getOrNull(cursor)?.takeIf { it.at <= center && center < it.until }
                    ?.kind?.let(TargetStatus::fromWire) ?: TargetStatus.UNKNOWN
            }
            WidgetAggregate(TargetHistoryPresentation.version(id), TargetPreferences.endpointRevision(ctx, id),
                TargetPersistence.active?.generationId, now / MINUTE_MS,
                CoverageStats.from(stats, CoverageStats.DAY_MS).percent, bins)
        }
        values = values + (id to value)
    }
}
