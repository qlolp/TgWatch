package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class RegionalVerificationTest {
    private val connection = RegionalConnection("0123456789abcdef0123456789abcdef", RegionalRoute.VPN)
    private fun note(layer: RegionalVerificationLayer = RegionalVerificationLayer.BROWSER,
        outcome: RegionalVerificationOutcome = RegionalVerificationOutcome.NO_EXPLICIT_ERROR) =
        RegionalVerification(RegionalService.GEMINI, layer, outcome, 1_000, 100, connection)

    @Test fun onlySupportedLayerOutcomePairsAreAccepted() {
        val browser = setOf(RegionalVerificationOutcome.REGIONAL_ERROR, RegionalVerificationOutcome.SIGN_IN,
            RegionalVerificationOutcome.CHALLENGE, RegionalVerificationOutcome.NO_EXPLICIT_ERROR,
            RegionalVerificationOutcome.CLEARED)
        val feature = setOf(RegionalVerificationOutcome.REGIONAL_ERROR, RegionalVerificationOutcome.WORKS,
            RegionalVerificationOutcome.FAILED, RegionalVerificationOutcome.CLEARED)
        RegionalVerificationLayer.entries.forEach { layer ->
            RegionalVerificationOutcome.entries.forEach { outcome ->
                if (outcome in if (layer == RegionalVerificationLayer.BROWSER) browser else feature)
                    RegionalJournalCodec.validate(note(layer, outcome).entry())
                else assertThrows(IllegalArgumentException::class.java) { note(layer, outcome) }
            }
        }
    }

    @Test fun currentMeansSameLiveConnectionAndNoMoreThanFifteenMinutesByEitherClock() {
        val note = note()
        val ttl = RegionalVerification.TTL_MS
        assertTrue(note.current(connection, 1_000 + ttl, 100 + ttl))
        assertFalse(note.current(connection, 1_001 + ttl, 100 + ttl))
        assertFalse(note.current(connection, 1_000 + ttl, 101 + ttl))
        assertFalse(note.current(connection, 999, 101))
        assertFalse(note.current(connection, 1_001, 99))
        assertFalse(note.current(connection.copy(token = "abcdef0123456789abcdef0123456789"), 1_001, 101))
        assertFalse(note.current(connection.copy(route = RegionalRoute.NONE), 1_001, 101))
        assertThrows(IllegalArgumentException::class.java) { note.copy(checkedElapsed = -1) }
    }

    @Test fun clearAndRestartCannotReviveHistoricalNotesAsCurrent() {
        val state = RegionalVerificationState()
        state.record(note())
        assertNotNull(state.current(RegionalService.GEMINI, RegionalVerificationLayer.BROWSER, connection, 1_001, 101))
        val historical = RegionalJournalCodec.decode(RegionalJournalCodec.encode(listOf(note().entry())))
        assertEquals(1, historical.size)
        assertNull(RegionalVerificationState().current(RegionalService.GEMINI,
            RegionalVerificationLayer.BROWSER, connection, 1_001, 101))
        state.record(note(outcome = RegionalVerificationOutcome.CLEARED))
        assertNull(state.current(RegionalService.GEMINI, RegionalVerificationLayer.BROWSER, connection, 1_001, 101))
    }

    @Test fun fixedManualMetadataCannotMasqueradeAsAPublicReading() {
        val row = note().entry()
        assertEquals(RegionalEventKind.BROWSER, row.kind)
        assertNull(row.status)
        assertNull(row.httpCode)
        assertFalse(row.confirmed)
        assertThrows(IllegalArgumentException::class.java) { RegionalJournalCodec.encode(listOf(row.copy(
            status = RegionalStatus.WEB_REACHABLE))) }
        assertThrows(IllegalArgumentException::class.java) { RegionalJournalCodec.encode(listOf(row.copy(
            kind = RegionalEventKind.PUBLIC, status = RegionalStatus.WEB_REACHABLE))) }
        assertEquals(listOf(row), RegionalJournalCodec.decode(RegionalJournalCodec.encode(listOf(row))))
    }

    @Test fun oldPublicJournalRemainsReadableAndExplicitReportsRetainTheirObservationTimes() {
        val old = "TGRJ1\nPUBLIC\t1000\t${connection.token}\tVPN\t-\t-\tgemini\tUNKNOWN\t0\t403\t-\n"
        assertEquals(RegionalStatus.UNKNOWN, RegionalJournalCodec.decode(old).single().status)
        val row = note().entry()
        val rows = RegionalJournal.retained(listOf(row), row.copy(at = 2_000), 2_000)
        assertEquals(listOf(1_000L, 2_000L), rows.map { it.at })
    }
}
