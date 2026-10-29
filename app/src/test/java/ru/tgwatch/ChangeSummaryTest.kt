package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSummaryTest {
    private val scope = "a".repeat(32)
    private val now = ChangeSummary.WINDOW_MS + 1_000_000L
    private val from = now - ChangeSummary.WINDOW_MS

    private fun country(at: Long, iso: String) = RegionalJournalEntry(
        RegionalEventKind.COUNTRY, at, scope, RegionalRoute.WIFI, iso, at)

    private fun network(at: Long, route: RegionalRoute = RegionalRoute.VPN) = RegionalJournalEntry(
        RegionalEventKind.NETWORK, at, scope, route)

    private fun geo(at: Long, service: RegionalService = RegionalService.YOUTUBE) = RegionalJournalEntry(
        RegionalEventKind.PUBLIC, at, scope, RegionalRoute.WIFI, service = service,
        status = RegionalStatus.REGION_RESTRICTED)

    private fun episode(startedAt: Long) = OutageEpisode(startedAt, startedAt + 60_000, true, false,
        60_000, 0, 0, 0, 0)

    private fun okTelegram() = ChangeSummary.TargetFact(TargetId.TELEGRAM, TargetStatus.OK, 0)

    @Test fun quietDayWithOnlyOkTargetsProducesNoCard() {
        assertNull(ChangeSummary.text(ChangeSummary.Input(now, listOf(okTelegram()), emptyList(),
            emptyList(), emptyList(), emptyList())))
    }

    @Test fun targetOutagesEgressSwitchGeoAndCorrelationComposeOneNarrative() {
        val switchAt = now - 40 * 60_000L
        val input = ChangeSummary.Input(
            now = now,
            targets = listOf(
                ChangeSummary.TargetFact(TargetId.TELEGRAM, TargetStatus.OK, 4),
                ChangeSummary.TargetFact(TargetId.MATRIX, TargetStatus.OK, 0),
            ),
            countryChanges = listOf(ChangeSummary.CountryChange("RU", "NL", switchAt)),
            networkChanges = listOf(ChangeSummary.NetworkChange(RegionalRoute.VPN, switchAt)),
            geoBlocks = listOf(ChangeSummary.GeoBlockFact(RegionalService.YOUTUBE, 2)),
            outages = listOf(
                episode(switchAt + 60_000),
                episode(switchAt + 120_000),
                episode(switchAt + 180_000),
                episode(now - 3 * 60 * 60_000L),
            ),
        )
        val text = ChangeSummary.text(input)!!
        val keys = text.body.allKeys()
        assertEquals(TextKey.CHANGE_TITLE, text.title.key)
        assertTrue(keys.contains(TextKey.CHANGE_TARGET_OUTAGES))
        assertTrue(keys.contains(TextKey.CHANGE_TARGET_OK))
        assertTrue(keys.contains(TextKey.CHANGE_GEO_BLOCK))
        assertTrue(keys.contains(TextKey.CHANGE_EGRESS_SWITCH))
        assertTrue(keys.contains(TextKey.CHANGE_NETWORK_VPN))
        assertTrue(keys.contains(TextKey.CHANGE_CORRELATION))
        val correlation = ChangeSummary.correlate(input.outages,
            input.countryChanges.map { it.at } + input.networkChanges.map { it.at })
        assertEquals(4, correlation.outages)
        assertEquals(3, correlation.matched)
    }

    @Test fun countryChangesUsePriorBaselineAndSkipSameIso() {
        val rows = listOf(
            country(from - 60_000, "RU"),
            country(from + 1_000, "RU"),
            country(from + 2_000, "NL"),
            country(from + 3_000, "NL"),
            country(from + 4_000, "DE"),
        )
        val changes = ChangeSummary.countryChanges(rows, from, now)
        assertEquals(listOf(
            ChangeSummary.CountryChange("RU", "NL", from + 2_000),
            ChangeSummary.CountryChange("NL", "DE", from + 4_000),
        ), changes)
    }

    @Test fun geoBlocksCountRestrictedPublicEventsOnly() {
        val rows = listOf(
            geo(from + 1_000),
            geo(from + 2_000),
            geo(from + 3_000, RegionalService.GEMINI),
            RegionalJournalEntry(RegionalEventKind.PUBLIC, from + 4_000, scope, RegionalRoute.WIFI,
                service = RegionalService.YOUTUBE, status = RegionalStatus.WEB_REACHABLE),
            geo(from - 1_000),
        )
        assertEquals(
            listOf(
                ChangeSummary.GeoBlockFact(RegionalService.GEMINI, 1),
                ChangeSummary.GeoBlockFact(RegionalService.YOUTUBE, 2),
            ),
            ChangeSummary.geoBlocks(rows, from, now),
        )
    }

    @Test fun correlationRequiresTemporalProximityNotCausationClaim() {
        val change = now - 60_000
        val near = episode(change + ChangeSummary.CORRELATE_MS)
        val far = episode(change + ChangeSummary.CORRELATE_MS + 1)
        val before = episode(change - ChangeSummary.CORRELATE_MS)
        val tooEarly = episode(change - ChangeSummary.CORRELATE_MS - 1)
        val result = ChangeSummary.correlate(listOf(near, far, before, tooEarly), listOf(change))
        assertEquals(4, result.outages)
        assertEquals(2, result.matched)
        assertTrue(result.notable)
        assertFalse(ChangeSummary.correlate(listOf(far), listOf(change)).notable)
    }

    @Test fun ongoingTargetUsesPreciseDurationLine() {
        val text = ChangeSummary.text(ChangeSummary.Input(now,
            listOf(ChangeSummary.TargetFact(TargetId.TELEGRAM, TargetStatus.DOWN, 1, true, 90_000)),
            emptyList(), emptyList(), emptyList(), emptyList()))
        assertNotNull(text)
        assertTrue(text!!.body.allKeys().contains(TextKey.CHANGE_TARGET_ONGOING))
    }

    @Test fun firstKnownCountryUsesSetCopyNotSwitch() {
        val text = ChangeSummary.text(ChangeSummary.Input(now, listOf(okTelegram()),
            listOf(ChangeSummary.CountryChange(null, "DE", now - 1_000)), emptyList(), emptyList(),
            emptyList()))!!
        assertTrue(text.body.allKeys().contains(TextKey.CHANGE_EGRESS_SET))
        assertFalse(text.body.allKeys().contains(TextKey.CHANGE_EGRESS_SWITCH))
    }
}
