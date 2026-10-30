package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class IncidentCenterTest {
    private val stats = TimeStats(50, 10, 0, 20, 20, 10, 3, 20)
    private val episode = OutageEpisode(200, 300, true, false, 80, 20, 0, 0, 0)

    @Test fun timelineCombinesTargetsAndRegionalFactsWithoutPrivateEndpointText() {
        val rows = listOf(
            RegionalJournalEntry(RegionalEventKind.NETWORK, 250, "scope", RegionalRoute.WIFI),
            RegionalJournalEntry(RegionalEventKind.COUNTRY, 260, "scope", RegionalRoute.WIFI, country = "DE"),
            RegionalJournalEntry(RegionalEventKind.PUBLIC, 270, "scope", RegionalRoute.WIFI,
                service = RegionalService.YOUTUBE, status = RegionalStatus.REGION_RESTRICTED),
        )
        val value = IncidentCenter.build(mapOf(TargetId.TELEGRAM to listOf(episode)), rows,
            mapOf(TargetId.TELEGRAM to stats), 0, 300)
        assertEquals(listOf(IncidentCenter.Kind.GEO_BLOCK, IncidentCenter.Kind.COUNTRY,
            IncidentCenter.Kind.NETWORK, IncidentCenter.Kind.OUTAGE), value.events.map { it.kind })
        assertEquals(TargetId.TELEGRAM, value.events.last().targetId)
        assertEquals(20, value.coverage.single().coverage.unknownMs)
        assertNull(value.events.single { it.kind == IncidentCenter.Kind.OUTAGE }.route)
    }

    @Test fun episodesOutsideTheWindowAreExcludedButOverlapsRemain() {
        val old = episode.copy(startedAt = 10, endedAt = 20)
        val overlapping = episode.copy(startedAt = 90, endedAt = 120)
        val future = episode.copy(startedAt = 301, endedAt = 320)
        val value = IncidentCenter.build(mapOf(TargetId.TELEGRAM to listOf(old, overlapping, future)),
            emptyList(), mapOf(TargetId.TELEGRAM to stats), 100, 300)
        assertEquals(listOf(overlapping), value.events.map { it.episode })
    }

    @Test fun latestEventsAreBoundedAndCoverageKeepsEveryTarget() {
        val episodes = (0 until 250).map { episode.copy(startedAt = it.toLong(), endedAt = it.toLong() + 1) }
        val targets = TargetId.entries.associateWith { stats }
        val value = IncidentCenter.build(TargetId.entries.associateWith { episodes }, emptyList(), targets, 0, 300)
        assertEquals(IncidentCenter.MAX_EVENTS, value.events.size)
        assertEquals(TargetId.entries.size, value.coverage.size)
        assertTrue(value.events.zipWithNext().all { (newer, older) -> newer.at >= older.at })
    }
}
