package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class OfflineBackoffTest {
    @Test fun longOfflineWaitsGrowAndCapAtFiveMinutes() {
        val backoff = OfflineBackoff()
        assertEquals(listOf(30, 60, 120, 300, 300), (1..5).map { backoff.next("NO_NETWORK", 10) })
    }
    @Test fun profileFloorIsRespectedWithoutSlowingTelegramFailures() {
        val backoff = OfflineBackoff()
        assertEquals(60, backoff.next("NO_NETWORK", 60))
        assertEquals(60, backoff.next("NO_NETWORK", 60))
        assertEquals(10, backoff.next("TG_DOWN", 10))
        assertEquals(30, backoff.next("NO_NETWORK", 10))
    }
    @Test fun networkEventAndUnknownResetOfflineWait() {
        val backoff = OfflineBackoff()
        repeat(5) { backoff.next("NO_NETWORK", 30) }
        backoff.reset()
        assertEquals(30, backoff.next("NO_NETWORK", 30))
        backoff.next("NO_NETWORK", 30)
        assertEquals(15, backoff.next("UNKNOWN", 15))
        assertEquals(30, backoff.next("NO_NETWORK", 15))
    }
}
