package ru.tgwatch

import android.content.Context

internal data class IncidentDisplay(val version: Long, val at: Long, val cards: List<String>) {
    companion object {
        private val MAX_DAYS get() = RetentionPolicy.maxDays()
        fun load(ctx: Context, days: Int, id: TargetId = TargetId.TELEGRAM): IncidentDisplay = synchronized(History) {
            val now = System.currentTimeMillis()
            val rows = TargetHistories.rows(ctx, id, now)
            val cards = OutageEpisodes.derive(rows, now - days.coerceIn(1, MAX_DAYS) * CoverageStats.DAY_MS, now)
                .map { AndroidText.resolve(ctx, IncidentPresentation.describe(it, targetId = id)) }
            IncidentDisplay(TargetHistoryPresentation.version(id), now, cards)
        }
    }
}
