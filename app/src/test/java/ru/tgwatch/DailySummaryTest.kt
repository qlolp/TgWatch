package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DailySummaryTest {
    private val hour = 3_600_000L
    private fun stats(okMs: Long = 0, downMs: Long = 0, partialMs: Long = 0, offlineMs: Long = 0,
        unknownMs: Long = 0, longest: Long = 0, checks: Int = 0, latency: Long = -1, outages: Int = 0,
        lastOutage: Long = 0, ongoing: Boolean = false, offlineCount: Int = 0) =
        TimeStats(okMs, downMs, partialMs, offlineMs, unknownMs, longest, checks, latency,
            outageCount = outages, lastOutageMs = lastOutage, lastOutageOngoing = ongoing, offlineCount = offlineCount)

    @Test fun noChecksMeansNoNotification() {
        assertNull(DailySummary.text(stats(unknownMs = 24 * hour)))
    }

    private fun lines(text: DailySummary.Text) = text.body.args.map { (it as TextArgument.Value).value }

    @Test fun quietDayIsShortAndHidesZeroSections() {
        val text = DailySummary.text(stats(okMs = 24 * hour, checks = 2880, latency = 142))!!
        assertEquals(TextKey.DAILY_TITLE_PERCENT, text.title.key)
        assertEquals(TextArgument.Decimal(100.0), text.title.args.last())
        assertEquals(listOf(TextKey.DAILY_NO_OUTAGES, TextKey.DAILY_LATENCY, TextKey.DAILY_CHECKS),
            lines(text).map { it.key })
        assertEquals(listOf(TextArgument.Number(142)), lines(text)[1].args)
        assertEquals(listOf(TextArgument.Number(2880)), lines(text).last().args)
    }

    @Test fun outagesOfflineAndGapsAreReported() {
        val measured = stats(okMs = 20 * hour, downMs = 10 * 60000L, partialMs = 5 * 60000L,
            offlineMs = 15 * 60000L, unknownMs = 3 * hour, longest = 4 * 60000L, checks = 2000,
            latency = 210, outages = 2, offlineCount = 3)
        val text = DailySummary.text(measured)!!
        assertEquals(TextArgument.Decimal(measured.uptimePercent), text.title.args.last())
        val values = lines(text)
        assertEquals(TextKey.DAILY_LONGEST, values.first().key)
        assertTrue(values.first().args.contains(TextArgument.Number(2)))
        assertTrue(values.first().args.contains(TextArgument.Duration(DurationParts.from(240000))))
        assertTrue(values.any { it.key == TextKey.DAILY_PARTIAL &&
            it.args == listOf(TextArgument.Duration(DurationParts.from(300000))) })
        assertTrue(values.any { it.key == TextKey.DAILY_OFFLINE &&
            it.args == listOf(TextArgument.Duration(DurationParts.from(900000))) })
        assertTrue(values.any { it.key == TextKey.DAILY_UNOBSERVED &&
            it.args == listOf(TextArgument.Duration(DurationParts.from(3 * hour))) })
        assertEquals(listOf(TextArgument.Number(2000)), values.last().args)
    }

    @Test fun ongoingOutageSaysSo() {
        val text = DailySummary.text(stats(okMs = hour, downMs = 90000, checks = 100, outages = 1,
            lastOutage = 90000, ongoing = true, longest = 90000))!!
        assertEquals(TextKey.DAILY_ONGOING, lines(text).first().key)
        assertTrue(lines(text).first().args.contains(TextArgument.Duration(DurationParts.from(90000), precise = true)))
    }

    @Test fun onlyOfflineDayHasNoPercent() {
        val text = DailySummary.text(stats(offlineMs = 2 * hour, checks = 10, offlineCount = 1))!!
        assertEquals(TextKey.DAILY_TITLE, text.title.key)
        assertFalse(lines(text).any { it.key == TextKey.DAILY_LATENCY })
    }

    private fun at(zone: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int) = Calendar.getInstance(zone).apply {
        clear(); set(y, m - 1, d, h, min)
    }.timeInMillis

    @Test fun nextAtIsTodayBeforeTargetAndTomorrowAtOrAfterIt() {
        val zone = TimeZone.getTimeZone("Europe/Moscow")
        assertEquals(at(zone, 2026, 10, 11, 21, 0), DailySummary.nextAt(at(zone, 2026, 10, 11, 20, 59), 21 * 60, zone))
        assertEquals(at(zone, 2026, 10, 12, 21, 0), DailySummary.nextAt(at(zone, 2026, 10, 11, 21, 0), 21 * 60, zone))
        assertEquals(at(zone, 2026, 10, 12, 0, 0), DailySummary.nextAt(at(zone, 2026, 10, 11, 23, 30), 0, zone))
    }

    @Test fun nextAtKeepsLocalTimeAcrossDstChange() {
        val zone = TimeZone.getTimeZone("Europe/Berlin")
        // 2026-10-25 03:00 CEST becomes 02:00 CET: that day is 25 hours long.
        val next = DailySummary.nextAt(at(zone, 2026, 10, 24, 22, 0), 21 * 60, zone)
        assertEquals(at(zone, 2026, 10, 25, 21, 0), next)
        assertEquals(25 * hour, next - at(zone, 2026, 10, 24, 21, 0))
    }

    @Test fun nextDayRestoresChosenTimeAfterSpringDstGap() {
        val zone = TimeZone.getTimeZone("Europe/Berlin")
        // 02:30 does not exist on March 29. Its normalization to 03:30 must not
        // change the selected 02:30 time on the following, ordinary day.
        assertEquals(at(zone, 2026, 3, 30, 2, 30),
            DailySummary.nextAt(at(zone, 2026, 3, 29, 4, 0), 2 * 60 + 30, zone))
    }

    @Test fun scheduledSummaryNeedsOptInMonitoringAndNoDeliveryToday() {
        assertTrue(DailySummary.due(force = false, optedIn = true, monitoring = true, deliveredToday = false))
        assertFalse("Monitoring is off", DailySummary.due(force = false, optedIn = true, monitoring = false, deliveredToday = false))
        assertFalse(DailySummary.due(force = false, optedIn = false, monitoring = true, deliveredToday = false))
        assertFalse(DailySummary.due(force = false, optedIn = true, monitoring = true, deliveredToday = true))
    }

    @Test fun manualPreviewIgnoresOptInMonitoringAndDeduplication() {
        assertTrue(DailySummary.due(force = true, optedIn = false, monitoring = false, deliveredToday = true))
    }

    @Test fun timeLabelPadsHoursAndMinutes() {
        assertEquals("07:05", DailySummary.timeLabel(425))
        assertEquals("21:00", DailySummary.timeLabel(DailySummary.DEFAULT_MINUTE))
    }
}
