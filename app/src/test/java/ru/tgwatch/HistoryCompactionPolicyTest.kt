package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class HistoryCompactionPolicyTest {
    @Test fun pruningDoesNotForceReplacementOnEveryAppend() {
        val policy = HistoryCompaction()
        policy.savedReplacement(0)
        repeat(8) {
            policy.onPruned()
            assertFalse(policy.needsReplacement((it + 1) * 10_000L, 60_480 + it, 4_000_000L))
            policy.savedAppend()
        }
    }

    @Test fun repairDebtSurvivesFailedDurabilityAndAppend() {
        val policy = HistoryCompaction()
        policy.savedReplacement(0)
        policy.failedWrite()
        assertTrue(policy.needsReplacement(1, 1, 10))
        policy.savedAppend()
        assertTrue(policy.needsReplacement(2, 2, 20))
        policy.savedReplacement(3)
        assertFalse(policy.needsReplacement(4, 2, 20))
    }

    @Test fun countTimeAndPhysicalBoundsRequireCompaction() {
        val policy = HistoryCompaction()
        policy.savedReplacement(100)
        repeat(1_024) { policy.savedAppend() }
        assertTrue(policy.needsReplacement(101, 1_024, 100))
        policy.savedReplacement(100)
        assertTrue(policy.needsReplacement(100 + 6 * 60 * 60_000L, 1, 10))
        assertTrue(policy.needsReplacement(99, 1, 10))
        assertTrue(policy.needsReplacement(101, 66_025, 10))
        assertTrue(policy.needsReplacement(101, 1, 16 * 1024 * 1024L + 1))
    }
}
