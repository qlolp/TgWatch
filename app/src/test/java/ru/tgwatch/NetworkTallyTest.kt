package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTallyTest {
    private fun NetworkTally.add(day: Long, kind: NetworkKind, ok: Int = 0, partial: Int = 0, down: Int = 0) {
        repeat(ok) { record(day, kind, "OK") }
        repeat(partial) { record(day, kind, "PARTIAL") }
        repeat(down) { record(day, kind, "TG_DOWN") }
    }

    private fun keys(value: TextValue): List<TextKey> =
        value.args.map { (it as TextArgument.Value).value.key }

    @Test fun onlyTelegramVerdictsOnAKnownNetworkAreCounted() {
        val tally = NetworkTally()
        assertFalse(tally.record(10, NetworkKind.WIFI, "UNKNOWN"))
        assertFalse(tally.record(10, NetworkKind.WIFI, "NO_NETWORK"))
        assertFalse(tally.record(10, NetworkKind.UNKNOWN, "OK"))
        assertTrue(tally.record(10, NetworkKind.WIFI, "OK"))
        assertEquals(mapOf(NetworkKind.WIFI to NetCounts(ok = 1)), tally.totals(10, 1))
    }

    @Test fun periodsSumDaysAndOldDaysAreDropped() {
        val tally = NetworkTally()
        tally.add(1, NetworkKind.CELLULAR, ok = 5)
        tally.add(5, NetworkKind.CELLULAR, ok = 2, down = 1)
        tally.add(7, NetworkKind.CELLULAR, ok = 1, partial = 1)
        assertEquals(NetCounts(ok = 1, partial = 1), tally.totals(7, 1).getValue(NetworkKind.CELLULAR))
        assertEquals(NetCounts(ok = 8, partial = 1, down = 1), tally.totals(7, 7).getValue(NetworkKind.CELLULAR))
        tally.add(8, NetworkKind.WIFI, ok = 1)
        assertEquals("Day 1 is older than seven days on day 8", NetCounts(ok = 3, partial = 1, down = 1),
            tally.totals(8, 7).getValue(NetworkKind.CELLULAR))
    }

    @Test fun encodingRoundTripsAndSkipsDamagedLines() {
        val tally = NetworkTally()
        tally.add(3, NetworkKind.VPN, ok = 4, down = 2)
        tally.add(3, NetworkKind.WIFI, partial = 1)
        val text = tally.encode()
        assertEquals(tally.snapshot, NetworkTally.decode(text).snapshot)
        val damaged = NetworkTally.decode("$text\n3,SATELLITE,1,0,0\n3,UNKNOWN,1,0,0\n3,WIFI,-1,0,0\nx\n4,CELLULAR,1,2")
        assertEquals(tally.snapshot, damaged.snapshot)
    }

    @Test fun describesOnlyWhenTwoNetworksHaveEnoughChecks() {
        assertNull(NetworkTally.describe(mapOf(NetworkKind.WIFI to NetCounts(ok = 100))))
        assertNull(NetworkTally.describe(mapOf(NetworkKind.WIFI to NetCounts(ok = 100),
            NetworkKind.VPN to NetCounts(ok = 4))))
        val text = NetworkTally.describe(mapOf(NetworkKind.WIFI to NetCounts(ok = 9, down = 1),
            NetworkKind.CELLULAR to NetCounts(ok = 5)))!!
        assertEquals(listOf(TextKey.HISTORY_BY_NETWORK), keys(text))
    }

    @Test fun pointsOutWhenOnlyTheVpnWorks() {
        val vpnOnly = NetworkTally.describe(mapOf(NetworkKind.CELLULAR to NetCounts(ok = 10, down = 30),
            NetworkKind.VPN to NetCounts(ok = 40)))!!
        assertEquals(listOf(TextKey.HISTORY_BY_NETWORK, TextKey.HISTORY_VPN_HELPS), keys(vpnOnly))
        val fine = NetworkTally.describe(mapOf(NetworkKind.CELLULAR to NetCounts(ok = 39, down = 1),
            NetworkKind.VPN to NetCounts(ok = 40)))!!
        assertEquals(listOf(TextKey.HISTORY_BY_NETWORK), keys(fine))
    }

    @Test fun networkSummaryExplicitlyLabelsItsCalendarPeriod() {
        val text = NetworkTally.describe(mapOf(NetworkKind.WIFI to NetCounts(ok = 5),
            NetworkKind.CELLULAR to NetCounts(ok = 5)))!!
        val line = (text.args.first() as TextArgument.Value).value
        val period = (line.args.first() as TextArgument.Value).value
        assertEquals("HISTORY_NETWORK_TODAY", period.key.name)
        assertEquals("The period and the measured percentages must be separate arguments", 2, line.args.size)
    }

    @Test fun weeklyNetworkSummaryIncludesTodayAndNamesItsCalendarWindow() {
        val totals = mapOf(NetworkKind.WIFI to NetCounts(ok = 5), NetworkKind.CELLULAR to NetCounts(ok = 5))
        val text = NetworkTally.describe(totals, days = 7)!!
        val line = (text.args.first() as TextArgument.Value).value
        val period = (line.args.first() as TextArgument.Value).value
        assertEquals(TextKey.HISTORY_NETWORK_CALENDAR_DAYS, period.key)
        assertEquals(listOf(TextArgument.Number(7)), period.args)
    }

    @Test fun calendarDaysFollowTheLocalDateAcrossDaylightSavingAndMidnight() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
            fun day(at: String) = NetworkStats.day(java.time.Instant.parse(at).toEpochMilli())
            val start = day("2026-10-24T22:30:00Z")
            assertEquals(start, day("2026-10-25T22:30:00Z"))
            assertEquals(start + 1, day("2026-10-25T23:30:00Z"))
            assertEquals(start, day("2026-10-24T22:30:00Z"))
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}
