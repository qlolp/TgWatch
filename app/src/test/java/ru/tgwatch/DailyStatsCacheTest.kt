package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DailyStatsCacheTest {
    private fun stats() = TimeStats(1, 1, 1, 1, 1, 1, 1, 1)
    @Test fun repeatedNotificationTicksDoNotReadHistory() {
        val cache = DailyStatsCache()
        var reads = 0
        val expected = stats()
        repeat(15) { tick ->
            assertSame(expected, cache.get(7, 120_000L + tick * 1_000L) { reads++; expected })
        }
        assertEquals(1, reads)
    }
    @Test fun newHistoryMinuteAndClockRollbackRefreshStatistics() {
        val cache = DailyStatsCache()
        var reads = 0
        cache.get(1, 120_000) { reads++; stats() }
        cache.get(2, 120_000) { reads++; stats() }
        cache.get(2, 180_000) { reads++; stats() }
        cache.get(2, 60_000) { reads++; stats() }
        assertEquals(4, reads)
    }

    @Test fun notificationSnapshotDoesNotWaitForABlockedHistoryRefresh() {
        val cache = DailyStatsCache()
        val previous = stats()
        cache.get(1, 120_000L) { previous }
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val read = CountDownLatch(1)
        val seen = AtomicReference<TimeStats?>()
        val failure = AtomicReference<Throwable?>()
        val refresher = thread {
            cache.get(2, 180_000L) {
                loading.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                stats()
            }
        }
        var reader: Thread? = null
        try {
            assertTrue(loading.await(2, TimeUnit.SECONDS))
            reader = thread {
                try { seen.set(cache.peek()) }
                catch (e: Throwable) { failure.set(e) }
                finally { read.countDown() }
            }
            assertTrue("Building a notification must not wait for disk/statistics on another thread",
                read.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertSame(previous, seen.get())
        } finally {
            release.countDown()
            refresher.join(2000)
            reader?.join(2000)
        }
    }

    @Test fun coldSnapshotDoesNotPretendThatHistoryIsKnown() {
        val cache = DailyStatsCache()
        assertNull(cache.peek())
        assertTrue(cache.needsRefresh(1, 120_000L))
        val expected = stats()
        cache.get(1, 120_000L) { expected }
        assertSame(expected, cache.peek())
        assertFalse(cache.needsRefresh(1, 120_001L))
        assertTrue(cache.needsRefresh(2, 120_001L))
        assertTrue(cache.needsRefresh(1, 180_000L))
    }
}
