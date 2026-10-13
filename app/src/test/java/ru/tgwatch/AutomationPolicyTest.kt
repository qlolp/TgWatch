package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class AutomationPolicyTest {
    private val token = "01".repeat(32)

    @Test fun disabledAndMissingCredentialsNeverAuthorize() {
        assertFalse(AutomationPolicy.authorized(false, token, token))
        assertFalse(AutomationPolicy.authorized(true, null, token))
        assertFalse(AutomationPolicy.authorized(true, token, null))
    }

    @Test fun exactBoundedTokenIsRequired() {
        assertTrue(AutomationPolicy.authorized(true, token, token))
        assertFalse(AutomationPolicy.authorized(true, token, "00".repeat(32)))
        assertFalse(AutomationPolicy.authorized(true, token, token + "0"))
        assertFalse(AutomationPolicy.authorized(true, token, "0".repeat(63)))
        assertFalse(AutomationPolicy.authorized(true, token, "x".repeat(64)))
    }

    @Test fun stateReadsAreRateLimitedButNeverBlockedByAnOlderClock() {
        val gap = AutomationPolicy.STATE_GAP_MS
        assertTrue(AutomationPolicy.stateAllowed(0L, null))
        assertTrue(AutomationPolicy.stateAllowed(gap, 0L))
        assertFalse(AutomationPolicy.stateAllowed(gap - 1, 0L))
        assertFalse(AutomationPolicy.stateAllowed(-1L, null))
        assertFalse(AutomationPolicy.stateAllowed(5L, Long.MIN_VALUE))
        // A monotonic clock must not run backwards inside one process.
        assertFalse(AutomationPolicy.stateAllowed(1L, 1_000L))
        assertTrue(AutomationPolicy.stateAllowed(1_000L, 500L, minGapMs = 500L))
    }

    @Test fun actionAllowlistIsExact() {
        assertTrue(AutomationPolicy.validAction(AutomationPolicy.ACTION_CHECK_NOW))
        assertTrue(AutomationPolicy.validAction(AutomationPolicy.ACTION_GET_STATE))
        assertFalse(AutomationPolicy.validAction("ru.tgwatch.action.START"))
        assertFalse(AutomationPolicy.validAction(null))
        assertFalse(AutomationPolicy.validAction("x".repeat(10000)))
    }

    @Test fun checksAreThrottledByElapsedTimeAndRollbackFailsClosed() {
        assertTrue(AutomationPolicy.checkAllowed(100, null))
        assertFalse(AutomationPolicy.checkAllowed(10099, 100))
        assertTrue(AutomationPolicy.checkAllowed(10100, 100))
        assertFalse(AutomationPolicy.checkAllowed(99, 100))
        assertFalse(AutomationPolicy.checkAllowed(-1, null))
        assertFalse(AutomationPolicy.checkAllowed(Long.MAX_VALUE, Long.MAX_VALUE - 1))
    }

    @Test fun stoppedAndUnobservedStatesAreStale() {
        assertTrue(AutomationPolicy.stale(false, 1000, 2000, 30))
        assertTrue(AutomationPolicy.stale(true, 0, 2000, 30))
        assertTrue(AutomationPolicy.stale(true, 3000, 2000, 30))
        assertFalse(AutomationPolicy.stale(true, 1000, 2000, 30))
        assertTrue(AutomationPolicy.stale(true, 1000, 1000000, 30))
        assertFalse(AutomationPolicy.stale(true, 1000, 600000, 300))
    }
}
