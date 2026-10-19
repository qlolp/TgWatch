package ru.tgwatch

import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class LastSuccessPolicyTest {
    @Test fun onlyPositivePublishedOkUpdatesLastSuccess() {
        assertEquals(2000L, LastSuccessPolicy.updated(1000, "OK", 2000))
        for (status in listOf("PARTIAL", "TG_DOWN", "NO_NETWORK", "UNKNOWN", "unexpected")) {
            assertEquals(1000L, LastSuccessPolicy.updated(1000, status, 2000))
            assertEquals(0L, LastSuccessPolicy.updated(0, status, 2000))
        }
        assertEquals(1000L, LastSuccessPolicy.updated(1000, "OK", 0))
        assertEquals(1000L, LastSuccessPolicy.updated(1000, "OK", -1))
    }

    @Test fun aRealOkAfterClockRollbackBecomesLatestPublishedSuccess() {
        assertEquals(500L, LastSuccessPolicy.updated(1000, "OK", 500))
        assertEquals(1000L, LastSuccessPolicy.updated(1000, "PARTIAL", 500))
    }

    @Test fun rollbackDoesNotInventAJustNowAge() {
        assertNull(LastSuccessPolicy.ageMs(2000, 1000))
        assertNull(LastSuccessPolicy.ageMs(0, 1000))
        assertNull(LastSuccessPolicy.ageMs(-1, 1000))
        assertEquals(0L, LastSuccessPolicy.ageMs(1000, 1000))
        assertEquals(1000L, LastSuccessPolicy.ageMs(1000, 2000))
    }

    @Test fun labelPreservesAbsoluteTimestampAndHonestRollbackMarkerAsTypedData() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(TextKey.LAST_SUCCESS_EMPTY, LastSuccessPolicy.label(0, 3000, utc).key)
        val observed = LastSuccessPolicy.label(2000, 3000, utc)
        assertEquals(TextKey.LAST_SUCCESS, observed.key)
        assertEquals(listOf(TextArgument.Number(2000), TextArgument.Text("UTC")), observed.args)
        assertEquals(TextKey.LAST_SUCCESS_CLOCK_CHANGED, LastSuccessPolicy.label(2000, 1000, utc).key)
    }
}
