package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class RegionalChangeWatchTest {
    private val request = RegionalWatchRequest(1_000, 1_000 + RegionalWatchRequest.TTL_MS)
    private val age = 600_000L
    private fun observe(watch: RegionalChangeWatch, at: Long, scope: String? = "a",
        status: RegionalStatus = RegionalStatus.WEB_REACHABLE, now: Long = at) =
        watch.observe(status, scope, at, now, age)

    @Test fun onlyTwoNewPostArmPublicResponsesAtLeastFifteenSecondsApartSatisfyTheGoal() {
        val watch = RegionalChangeWatch(request)
        observe(watch, 1_000)
        assertNull(watch.confirmationDue)
        observe(watch, 2_000)
        assertEquals(17_000L, watch.confirmationDue)
        assertFalse(watch.ready("a", 16_999, age))
        observe(watch, 16_999)
        assertFalse(watch.ready("a", 16_999, age))
        observe(watch, 17_000)
        assertTrue(watch.ready("a", 17_000, age))
        watch.delivered()
        assertFalse(watch.ready("a", 17_000, age))
    }

    @Test fun cachedSampleCannotBecomeASecondObservationJustByWaiting() {
        val watch = RegionalChangeWatch(request)
        observe(watch, 2_000)
        observe(watch, 2_000, now = 50_000)
        assertFalse(watch.ready("a", 50_000, age))
        observe(watch, 50_000)
        assertTrue(watch.ready("a", 50_000, age))
    }

    @Test fun everyNonPublicResponseAndUnknownConnectionRequiresANewPair() {
        RegionalStatus.entries.filter { it != RegionalStatus.WEB_REACHABLE }.forEach { status ->
            val watch = RegionalChangeWatch(request)
            observe(watch, 2_000)
            observe(watch, 17_000, status = status)
            assertFalse(watch.ready("a", 17_000, age))
            observe(watch, 18_000)
            assertFalse(watch.ready("a", 18_000, age))
            observe(watch, 33_000)
            assertTrue(watch.ready("a", 33_000, age))
        }
        val watch = RegionalChangeWatch(request)
        observe(watch, 2_000)
        observe(watch, 17_000, scope = null)
        assertFalse(watch.ready(null, 17_000, age))
    }

    @Test fun changedNetworkConfigurationOrProcessCannotReuseTheOldPositive() {
        val watch = RegionalChangeWatch(request)
        observe(watch, 2_000, "network-a-config-1")
        observe(watch, 17_000, "network-b-config-2")
        assertFalse(watch.ready("network-b-config-2", 17_000, age))
        observe(watch, 32_000, "network-b-config-2")
        assertTrue(watch.ready("network-b-config-2", 32_000, age))
        assertFalse(watch.ready("network-c", 32_000, age))
        assertFalse(RegionalChangeWatch(request).ready("network-b-config-2", 32_000, age))
        watch.resetEvidence()
        assertFalse(watch.ready("network-b-config-2", 32_000, age))
    }

    @Test fun blockedDeliveryKeepsTheGoalButCannotReuseStaleOrWidelySeparatedResponses() {
        val watch = RegionalChangeWatch(request)
        observe(watch, 2_000)
        observe(watch, 17_000)
        assertTrue(watch.ready("a", 17_000, age))
        assertTrue(watch.ready("a", 18_000, age))
        assertFalse(watch.ready("a", 17_001 + age, age))
        observe(watch, 17_001 + age)
        assertFalse(watch.ready("a", 17_001 + age, age))
        observe(watch, 32_001 + age)
        assertTrue(watch.ready("a", 32_001 + age, age))
    }

    @Test fun expiryClockReversalAndFutureSamplesNeverSatisfyTheGoal() {
        val watch = RegionalChangeWatch(request)
        observe(watch, 2_000)
        observe(watch, 17_000, now = 16_000)
        assertFalse(watch.ready("a", 17_000, age))
        observe(watch, 18_000)
        observe(watch, 17_500)
        assertFalse(watch.ready("a", 18_000, age))
        assertFalse(request.active(999))
        assertFalse(request.active(request.deadlineElapsed))
        assertThrows(IllegalArgumentException::class.java) { RegionalWatchRequest(-1, 100) }
        assertThrows(IllegalArgumentException::class.java) { RegionalWatchRequest(0, Long.MAX_VALUE) }
    }

    @Test fun persistedIntentIsBoundToKnownBootAndEnabledProfileRevision() {
        val stored = RegionalWatchMetadata(request, 2, 3)
        assertTrue(stored.current(2, 3, true, 2_000))
        assertFalse(stored.current(3, 3, true, 2_000))
        assertFalse(stored.current(-1, 3, true, 2_000))
        assertFalse(stored.current(2, 4, true, 2_000))
        assertFalse(stored.current(2, 3, false, 2_000))
        assertFalse(stored.current(2, 3, true, request.deadlineElapsed))
    }

    @Test fun rebootRebasesOnlyTheRemainingDeadlineAndRejectsClockRollbackOrExpiry() {
        val rebased = request.afterReboot(500, 10_000, 10_000 + RegionalWatchRequest.TTL_MS, 25_000)
        assertEquals(500L, rebased?.armedElapsed)
        assertEquals(500L + RegionalWatchRequest.TTL_MS - 15_000, rebased?.deadlineElapsed)
        assertNull(request.afterReboot(500, 10_000, 10_000 + RegionalWatchRequest.TTL_MS, 9_999))
        assertNull(request.afterReboot(500, 25_000, 10_000 + RegionalWatchRequest.TTL_MS, 24_999))
        assertNull(request.afterReboot(500, 10_000, 10_000 + RegionalWatchRequest.TTL_MS,
            10_000 + RegionalWatchRequest.TTL_MS))
        assertNull(request.afterReboot(500, 10_000, 10_000 + RegionalWatchRequest.TTL_MS + 1, 25_000))
    }
}
