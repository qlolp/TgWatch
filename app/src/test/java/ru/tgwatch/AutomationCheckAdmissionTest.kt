package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class AutomationCheckAdmissionTest {
    @Test fun unavailableHandoffCannotSpendTheNextSuccessfulChecksBudget() {
        var lastCheck: Long? = null
        var writes = 0
        var available = false
        val record: () -> Boolean = { writes++; lastCheck = 100; true }
        val request: (() -> Boolean) -> Boolean = { admission -> available && admission() }

        assertEquals(AutomationCheckAdmission.Result.UNAVAILABLE,
            AutomationCheckAdmission.request(100, lastCheck, request, record))
        assertEquals("A stopped or rejected service must not spend the throttle", 0, writes)
        assertNull(lastCheck)

        available = true
        assertEquals(AutomationCheckAdmission.Result.ACCEPTED,
            AutomationCheckAdmission.request(100, lastCheck, request, record))
        assertEquals(1, writes)
        assertEquals(100L, lastCheck)
        assertEquals(AutomationCheckAdmission.Result.RATE_LIMITED,
            AutomationCheckAdmission.request(101, lastCheck, request, record))
        assertEquals(1, writes)
    }

    @Test fun failedDurableBudgetCannotAdmitWorkEvenIfPreferencesChangedInMemory() {
        var lastCheck: Long? = null
        var admitted = 0
        val record: () -> Boolean = { lastCheck = 100; false }
        val request: (() -> Boolean) -> Boolean = { admission ->
            admission().also { if (it) admitted++ }
        }

        assertEquals(AutomationCheckAdmission.Result.UNAVAILABLE,
            AutomationCheckAdmission.request(100, lastCheck, request, record))
        assertEquals("A disk failure must fail closed before any real check is admitted", 0, admitted)
        assertEquals(AutomationCheckAdmission.Result.RATE_LIMITED,
            AutomationCheckAdmission.request(101, lastCheck, request, record))
        assertEquals(0, admitted)
    }

    @Test fun rollbackAndGapBoundaryKeepTheExistingMonotonicThrottle() {
        val request: (() -> Boolean) -> Boolean = { it() }
        var writes = 0
        val record: () -> Boolean = { writes++; true }
        assertEquals(AutomationCheckAdmission.Result.RATE_LIMITED,
            AutomationCheckAdmission.request(99, 100, request, record))
        assertEquals(AutomationCheckAdmission.Result.RATE_LIMITED,
            AutomationCheckAdmission.request(10_099, 100, request, record))
        assertEquals(AutomationCheckAdmission.Result.ACCEPTED,
            AutomationCheckAdmission.request(10_100, 100, request, record))
        assertEquals(1, writes)
    }
}
