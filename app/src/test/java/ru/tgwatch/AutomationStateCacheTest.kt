package ru.tgwatch

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutomationStateCacheTest {
    @Before fun clear() {
        AutomationStateCache.clear()
    }

    @After fun reset() {
        AutomationStateCache.clear()
    }

    @Test fun freshCacheIsServedWithoutRecompute() {
        assertTrue(AutomationStateCache.beginCompute(1_000))
        val first = AutomationStateSnapshot("OK", 10, stale = false, running = true)
        AutomationStateCache.endCompute(1_000, first)
        assertEquals(first, AutomationStateCache.take(1_500))
        assertFalse("A fresh cache must block a competing compute", AutomationStateCache.beginCompute(1_500))
    }

    @Test fun expiredCacheRequiresTheComputeGap() {
        assertTrue(AutomationStateCache.beginCompute(0))
        AutomationStateCache.endCompute(0, AutomationStateSnapshot("OK", 0, stale = true, running = false))
        val expiredAt = AutomationPolicy.STATE_CACHE_MS
        assertNull(AutomationStateCache.take(expiredAt))
        assertTrue("After expiry the first recompute is allowed", AutomationStateCache.beginCompute(expiredAt))
        AutomationStateCache.endCompute(expiredAt, null)
        assertFalse("A second recompute must wait for the compute gap",
            AutomationStateCache.beginCompute(expiredAt + AutomationPolicy.STATE_GAP_MS - 1))
        assertTrue(AutomationStateCache.beginCompute(expiredAt + AutomationPolicy.STATE_GAP_MS))
        AutomationStateCache.endCompute(expiredAt + AutomationPolicy.STATE_GAP_MS, null)
    }

    @Test fun concurrentBeginComputeIsSingleFlight() {
        assertTrue(AutomationStateCache.beginCompute(10))
        assertFalse(AutomationStateCache.beginCompute(10))
        assertFalse(AutomationStateCache.beginCompute(10 + AutomationPolicy.STATE_GAP_MS))
        AutomationStateCache.endCompute(10, null)
        assertTrue(AutomationStateCache.beginCompute(10 + AutomationPolicy.STATE_GAP_MS))
        AutomationStateCache.endCompute(10 + AutomationPolicy.STATE_GAP_MS, null)
    }
}
