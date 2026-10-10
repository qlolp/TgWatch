package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRulesTest {

    @Test
    fun reachableCodes_include2xx3xx4xx_exclude5xx() {
        assertTrue(ProbeRules.isReachableHttpCode(200))
        assertTrue(ProbeRules.isReachableHttpCode(204))
        assertTrue(ProbeRules.isReachableHttpCode(301))
        assertTrue(ProbeRules.isReachableHttpCode(404))
        assertFalse(ProbeRules.isReachableHttpCode(500))
        assertFalse(ProbeRules.isReachableHttpCode(502))
        assertFalse(ProbeRules.isReachableHttpCode(503))
        assertFalse(ProbeRules.isReachableHttpCode(0))
    }

    @Test
    fun stale_afterThreeIntervals() {
        val now = 100_000L
        assertFalse(ProbeRules.isStale(now - 10_000, now, 15))
        assertFalse(ProbeRules.isStale(now - 40_000, now, 15))
        assertTrue(ProbeRules.isStale(now - 50_000, now, 15))
        assertTrue(ProbeRules.isStale(0, now, 15))
    }

    @Test
    fun stale_screenOffUsesMinuteScale() {
        val now = 200_000L
        assertFalse(ProbeRules.isStale(now - 90_000, now, 15, slowExpected = true))
        assertTrue(ProbeRules.isStale(now - 200_000, now, 15, slowExpected = true))
    }

    @Test
    fun nextInterval_slowsWhenScreenOffAndOk() {
        assertEquals(10, ProbeRules.nextIntervalSec(15, bad = true, screenOffSlow = true))
        assertEquals(60, ProbeRules.nextIntervalSec(15, bad = false, screenOffSlow = true))
        assertEquals(15, ProbeRules.nextIntervalSec(15, bad = false, screenOffSlow = false))
        assertEquals(120, ProbeRules.nextIntervalSec(120, bad = false, screenOffSlow = true))
    }

    @Test
    fun unmonitoredMinutes_countsGaps() {
        assertEquals(0, ProbeRules.unmonitoredMinutes(longArrayOf(10)))
        assertEquals(0, ProbeRules.unmonitoredMinutes(longArrayOf(10, 11, 12)))
        assertEquals(2, ProbeRules.unmonitoredMinutes(longArrayOf(10, 13)))
    }

    @Test
    fun quietHours_crossesMidnight() {
        assertTrue(ProbeRules.inQuietHours(23, 23, 8))
        assertTrue(ProbeRules.inQuietHours(2, 23, 8))
        assertFalse(ProbeRules.inQuietHours(12, 23, 8))
        assertFalse(ProbeRules.inQuietHours(8, 23, 8))
    }

    @Test
    fun quietHours_sameDayWindow() {
        assertTrue(ProbeRules.inQuietHours(14, 13, 17))
        assertFalse(ProbeRules.inQuietHours(12, 13, 17))
        assertFalse(ProbeRules.inQuietHours(13, 13, 13))
    }

    @Test
    fun quietHoursLabel_padsHours() {
        assertEquals("23:00–08:00", ProbeRules.quietHoursLabel(23, 8))
        assertEquals("00:00–07:00", ProbeRules.quietHoursLabel(0, 7))
        assertEquals("23:00–00:00", ProbeRules.quietHoursLabel(25, -1))
    }

    @Test
    fun describeHttpFailure_mentionsCode() {
        assertEquals("сервер вернул ошибку HTTP 503", ProbeRules.describeHttpFailure(503))
    }
}
