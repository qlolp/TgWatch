package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tgwatch.SlowAlert.Action.ALERT
import ru.tgwatch.SlowAlert.Action.CLEAR
import ru.tgwatch.SlowAlert.Action.NONE

class SlowAlertTest {
    private fun SlowAlert.run(vararg checks: Pair<String, Long>, start: Long = 0L, stepMs: Long = 15_000L) =
        checks.mapIndexed { i, (status, latency) -> onCheck(status, latency, start + i * stepMs) }

    @Test fun alertsOnceAfterThreeSlowChecksInARow() {
        val alert = SlowAlert()
        assertEquals(listOf(NONE, NONE, ALERT, NONE),
            alert.run("OK" to 1200L, "OK" to 1500L, "OK" to 1000L, "OK" to 2000L))
        assertTrue(alert.active)
    }

    @Test fun oneFastCheckResetsTheStreak() {
        assertEquals(listOf(NONE, NONE, NONE, NONE, NONE),
            SlowAlert().run("OK" to 1200L, "OK" to 1200L, "OK" to 300L, "OK" to 1200L, "OK" to 1200L))
    }

    @Test fun clearsOnlyWellBelowTheThreshold() {
        val alert = SlowAlert()
        alert.run("OK" to 1500L, "OK" to 1500L, "OK" to 1500L)
        assertEquals(NONE, alert.onCheck("OK", 800L, 60_000L))
        assertTrue("Between the clear level and the threshold the warning stays", alert.active)
        assertEquals(CLEAR, alert.onCheck("OK", 200L, 75_000L))
        assertFalse(alert.active)
    }

    @Test fun anOutageClearsAndUnknownChangesNothing() {
        val alert = SlowAlert()
        alert.run("OK" to 1500L, "OK" to 1500L, "OK" to 1500L)
        assertEquals(NONE, alert.onCheck("UNKNOWN", -1L, 60_000L))
        assertTrue(alert.active)
        assertEquals(CLEAR, alert.onCheck("TG_DOWN", -1L, 75_000L))
        assertEquals(NONE, alert.onCheck("NO_NETWORK", -1L, 90_000L))
    }

    @Test fun failuresAndMissingLatencyNeverCountAsSlow() {
        assertEquals(List(4) { NONE }, SlowAlert().run("PARTIAL" to 5000L, "OK" to -1L, "OK" to 1500L, "OK" to 1500L))
    }

    @Test fun repeatedAlertsWaitForTheCooldown() {
        val alert = SlowAlert()
        alert.run("OK" to 1500L, "OK" to 1500L, "OK" to 1500L)
        assertEquals(CLEAR, alert.onCheck("OK", 100L, 60_000L))
        assertEquals(listOf(NONE, NONE, NONE), alert.run("OK" to 1500L, "OK" to 1500L, "OK" to 1500L, start = 75_000L))
        assertEquals(ALERT, alert.onCheck("OK", 1500L, SlowAlert.COOLDOWN_MS + 30_000L))
    }
}
