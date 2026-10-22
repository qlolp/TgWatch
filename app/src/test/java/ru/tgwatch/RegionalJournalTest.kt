package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class RegionalJournalTest {
    private val scope = "0123456789abcdef0123456789abcdef"
    private fun row(at: Long = 100) = RegionalJournalEntry(RegionalEventKind.PUBLIC, at,
        scope, RegionalRoute.VPN, "DE", 90, RegionalService.GEMINI,
        RegionalStatus.REGION_RESTRICTED, true, 403)

    @Test fun fixedMetadataRoundTripsWithoutNetworkIdentifiersOrResponseText() {
        val entries = listOf(RegionalJournalEntry(RegionalEventKind.NETWORK, 99, scope, RegionalRoute.VPN),
            RegionalJournalEntry(RegionalEventKind.COUNTRY, 99, scope, RegionalRoute.VPN, "DE", 90),
            row(), row(101).copy(status = RegionalStatus.WEB_REACHABLE, confirmed = false,
            httpCode = 200))
        val encoded = RegionalJournalCodec.encode(entries)
        assertEquals(entries, RegionalJournalCodec.decode(encoded))
        assertFalse(encoded.contains("https:"))
        assertFalse(encoded.contains("networkHandle"))
        assertFalse(encoded.contains("149.154"))
    }

    @Test fun malformedFieldsAndUnsupportedVersionsAreRejectedAsAWhole() {
        val good = RegionalJournalCodec.encode(listOf(row()))
        for (bad in listOf(good.replace("TGRJ1", "TGRJ9"), good.replace("VPN", "tunnel://secret"),
            good.replace("DE", "ZZ"), good.replace(scope, "1.2.3.4"),
            good.replace("REGION_RESTRICTED", "some page text"), good + "truncated",
            "x".repeat(RegionalJournalCodec.MAX_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { RegionalJournalCodec.decode(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegionalJournalCodec.encode(listOf(row().copy(confirmed = true, status = RegionalStatus.UNKNOWN)))
        }
    }

    @Test fun transitionsDeduplicateButConfirmationAndCountryChangesRemainEvents() {
        val first = row().copy(confirmed = false)
        var rows = RegionalJournal.retained(emptyList(), first, 100)
        rows = RegionalJournal.retained(rows, first.copy(at = 110), 110)
        assertEquals(1, rows.size)
        rows = RegionalJournal.retained(rows, row(120), 120)
        rows = RegionalJournal.retained(rows, row(130).copy(country = "NL"), 130)
        assertEquals(listOf(false, true, true), rows.map { it.confirmed })
        assertEquals(listOf("DE", "DE", "NL"), rows.map { it.country })
    }

    @Test fun retentionIsBoundedAndClockRollbackKeepsInsertionOrder() {
        val now = RegionalJournal.RETENTION_MS + 1_000
        val old = row(1).copy(countryAt = 0)
        val newest = row(now).copy(countryAt = now)
        assertEquals(listOf(newest), RegionalJournal.retained(listOf(old), newest, now))
        val rolled = newest.copy(at = now - 100, country = null, countryAt = null,
            status = RegionalStatus.AUTH_REQUIRED, confirmed = false)
        assertEquals(listOf(newest, rolled), RegionalJournal.retained(listOf(newest), rolled, now - 100))
        val many = (0..RegionalJournal.MAX_ROWS).map { row(it.toLong() + 100) }
        assertEquals(RegionalJournal.MAX_ROWS, RegionalJournal.retained(many, null, now = 2_000).size)
    }
}
