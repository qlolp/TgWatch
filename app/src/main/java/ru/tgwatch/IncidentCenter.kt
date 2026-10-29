package ru.tgwatch

/** Language-neutral, bounded cross-target timeline. It contains no endpoint or diagnostic text. */
internal object IncidentCenter {
    const val MAX_EVENTS = 200

    enum class Kind { OUTAGE, NETWORK, COUNTRY, GEO_BLOCK }

    data class Event(
        val at: Long,
        val until: Long,
        val kind: Kind,
        val targetId: TargetId? = null,
        val route: RegionalRoute? = null,
        val country: String? = null,
        val service: RegionalService? = null,
        val episode: OutageEpisode? = null,
    )

    data class TargetCoverage(val id: TargetId, val coverage: ObservationCoverage)
    data class Snapshot(val events: List<Event>, val coverage: List<TargetCoverage>)

    fun build(
        episodes: Map<TargetId, List<OutageEpisode>>,
        journal: List<RegionalJournalEntry>,
        stats: Map<TargetId, TimeStats>,
        from: Long,
        until: Long,
    ): Snapshot {
        require(from >= 0L && until >= from)
        val events = mutableListOf<Event>()
        episodes.forEach { (id, rows) ->
            rows.forEach { episode ->
                events += Event(episode.startedAt, episode.endedAt, Kind.OUTAGE, targetId = id, episode = episode)
            }
        }
        journal.asSequence().filter { it.at in from..until }.forEach { row ->
            when (row.kind) {
                RegionalEventKind.NETWORK -> events += Event(row.at, row.at, Kind.NETWORK, route = row.route)
                RegionalEventKind.COUNTRY -> if (row.country != null && EgressCountry.isValid(row.country)) {
                    events += Event(row.at, row.at, Kind.COUNTRY, country = row.country)
                }
                RegionalEventKind.PUBLIC -> if (row.status == RegionalStatus.REGION_RESTRICTED &&
                    row.service != null) {
                    events += Event(row.at, row.at, Kind.GEO_BLOCK, service = row.service)
                }
                else -> Unit
            }
        }
        val sorted = events.sortedWith(compareByDescending<Event> { it.at }.thenBy { it.kind.ordinal }
            .thenBy { it.targetId?.ordinal ?: -1 }).take(MAX_EVENTS)
        val coverage = stats.map { (id, value) ->
            TargetCoverage(id, ObservationCoverage.from(value, until - from))
        }.sortedBy { it.id.ordinal }
        return Snapshot(sorted, coverage)
    }
}
