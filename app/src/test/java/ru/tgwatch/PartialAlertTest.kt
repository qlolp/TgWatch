package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class PartialAlertTest {
    @Test fun partialAlertIsIndependentAndOptIn() {
        assertFalse(AlertRules.shouldAlarm("PARTIAL", true, true, false, true, 1000, -1))
        assertTrue(AlertRules.shouldAlarm("PARTIAL", false, false, true, true, 1000, -1))
        assertFalse(AlertRules.shouldAlarm("TG_DOWN", false, false, true, true, 1000, -1))
        assertFalse(AlertRules.shouldAlarm("UNKNOWN", true, true, true, true, 1000, -1))
        assertFalse(AlertRules.shouldAlarm("OK", true, true, true, true, 1000, -1))
    }

    @Test fun allFailureAlertsRespectQuietHoursAndSharedFiveMinuteGap() {
        for (status in listOf("PARTIAL", "TG_DOWN", "NO_NETWORK")) {
            assertFalse(AlertRules.shouldAlarm(status, true, true, true, false, 500_000, -1))
            assertFalse(AlertRules.shouldAlarm(status, true, true, true, true, 300_999, 1000))
            assertTrue(AlertRules.shouldAlarm(status, true, true, true, true, 301_000, 1000))
        }
    }

    @Test fun partialRecoveryFollowsItsOptInWhileExistingRecoveryIsPreserved() {
        assertFalse(AlertRules.recovered("PARTIAL", "OK", false))
        assertTrue(AlertRules.recovered("PARTIAL", "OK", true))
        assertTrue(AlertRules.recovered("TG_DOWN", "OK", false))
        assertTrue(AlertRules.recovered("NO_NETWORK", "OK", false))
        assertFalse(AlertRules.recovered("UNKNOWN", "OK", true))
        assertFalse(AlertRules.recovered("OK", "OK", true))
        assertFalse(AlertRules.recovered("TG_DOWN", "PARTIAL", true))
    }
}
