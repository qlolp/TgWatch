package ru.tgwatch

import android.content.Context

internal data class ChangeSummaryDisplayKey(
    val historyVersion: Long,
    val regionalGeneration: Long,
    val minute: Long,
    val ids: List<TargetId>,
)

internal data class ChangeSummaryDisplay(val key: ChangeSummaryDisplayKey, val text: String?)

/** Builds the Main “what changed” card from history stats and the regional journal. */
internal object ChangeSummaryLoader {
    private const val VERSION_MIX = 31L
    private const val MINUTE_MS = 60_000L

    fun key(ctx: Context, now: Long): ChangeSummaryDisplayKey {
        val ids = TargetId.entries.filter { TargetPreferences.definition(ctx, it).enabled }
        val historyVersion = ids.fold(0L) { acc, id ->
            acc xor (TargetHistoryPresentation.version(id) * VERSION_MIX + id.ordinal)
        }
        return ChangeSummaryDisplayKey(historyVersion, RegionalRepository.generation(ctx),
            now / MINUTE_MS, ids)
    }

    fun load(ctx: Context, key: ChangeSummaryDisplayKey): ChangeSummaryDisplay {
        val now = System.currentTimeMillis().coerceAtLeast(0)
        val from = (now - ChangeSummary.WINDOW_MS).coerceAtLeast(0)
        val journal = RegionalRepository.snapshot(ctx).rows
        val country = ChangeSummary.countryChanges(journal, from, now)
        val network = ChangeSummary.networkChanges(journal, from, now)
        val geo = ChangeSummary.geoBlocks(journal, from, now)
        val stats = key.ids.associateWith { id ->
            (TargetHistories.stats(ctx, id, 1, now) as? HistoryRead.Ready)?.value
        }
        val targets = key.ids.map { id ->
            val value = stats[id]
            val status = TargetStateStore.current(ctx, id).status
            if (value == null) ChangeSummary.TargetFact(id, status, 0)
            else ChangeSummary.TargetFact(id, status, value.outageCount, value.lastOutageOngoing,
                value.lastOutageMs)
        }
        // Coincidence uses the primary Telegram episode list; other targets stay in the status lines.
        val outages = if (TargetId.TELEGRAM in key.ids) {
            (History.readIncidents(ctx, 1, now) as? HistoryRead.Ready)?.value
                ?.filter { it.startedAt in from..now }.orEmpty()
        } else emptyList()
        val narrative = ChangeSummary.text(ChangeSummary.Input(now, targets, country, network, geo, outages))
        val coverage = key.ids.mapNotNull { id -> stats[id]?.let { value ->
            IncidentCenter.TargetCoverage(id, ObservationCoverage.from(value, ChangeSummary.WINDOW_MS))
        } }
        val gaps = ObservationCoverageText.gaps(ctx, coverage).ifBlank { null }
        val resolved = listOfNotNull(narrative?.let {
            AndroidText.resolve(ctx, textLines(listOf(it.title, it.body)))
        } ?: gaps?.let { ctx.getString(R.string.change_summary_title) }, gaps).joinToString("\n")
        return ChangeSummaryDisplay(key, resolved.ifBlank { null })
    }
}
