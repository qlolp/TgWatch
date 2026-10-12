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

    @Test fun quietDayIsShortAndHidesZeroSections() {
        val text = DailySummary.text(stats(okMs = 24 * hour, checks = 2880, latency = 142))!!
        assertEquals("Telegram за сутки: 100 %", text.title)
        assertEquals("Сбоев Telegram не было\nСредний отклик: 142 мс\nПроверок: 2880", text.body)
    }

    @Test fun outagesOfflineAndGapsAreReported() {
        val text = DailySummary.text(stats(okMs = 20 * hour, downMs = 10 * 60_000L, partialMs = 5 * 60_000L,
            offlineMs = 15 * 60_000L, unknownMs = 3 * hour, longest = 4 * 60_000L, checks = 2000,
            latency = 210, outages = 2, offlineCount = 3))!!
        assertEquals("Telegram за сутки: 98,8 %", text.title)
        val lines = text.body.lines()
        assertEquals("Сбоев Telegram: 2, самый долгий 4 мин", lines[0])
        assertTrue("Частично доступен: 5 мин" in lines)
        assertTrue("Без интернета: 15 мин" in lines)
        assertTrue("Без наблюдения: 3 ч" in lines)
        assertEquals("Проверок: 2000", lines.last())
    }

    @Test fun ongoingOutageSaysSo() {
        val text = DailySummary.text(stats(okMs = hour, downMs = 90_000, checks = 100, outages = 1,
            lastOutage = 90_000, ongoing = true, longest = 90_000))!!
        assertEquals("Сбоев Telegram: 1, сбой продолжается 1 мин 30 с", text.body.lines()[0])
    }

    @Test fun onlyOfflineDayHasNoPercent() {
        val text = DailySummary.text(stats(offlineMs = 2 * hour, checks = 10, offlineCount = 1))!!
        assertEquals("Telegram за сутки", text.title)
        assertFalse(text.body.contains("Средний отклик"))
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

    @Test fun timeLabelPadsHoursAndMinutes() {
        assertEquals("07:05", DailySummary.timeLabel(425))
        assertEquals("21:00", DailySummary.timeLabel(DailySummary.DEFAULT_MINUTE))
    }
}
