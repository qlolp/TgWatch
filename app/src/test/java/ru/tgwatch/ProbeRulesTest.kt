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
    fun stale_afterTwoIntervals() {
        val now = 100_000L
        assertFalse(ProbeRules.isStale(now - 10_000, now, 15))
        assertTrue(ProbeRules.isStale(now - 40_000, now, 15))
        assertTrue(ProbeRules.isStale(0, now, 15))
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
    fun describeHttpFailure_mentionsCode() {
        assertEquals("сервер вернул ошибку HTTP 503", ProbeRules.describeHttpFailure(503))
    }
}
