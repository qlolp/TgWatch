package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetSlowAlertTest {
    private fun state(status: TargetStatus = TargetStatus.OK, latency: Long? = 1500) =
        TargetSnapshot(TargetId.MATRIX, status, 100, 100, latencyMs = latency)
    @Test fun unknownAndUnmeasuredBreakPendingStreak() {
        val alert = TargetSlowAlert { TargetAlertSettings(notifySlow = true) }
        repeat(2) { assertEquals(SlowAlert.Action.NONE, alert.onCheck(state(), true, it.toLong())) }
        alert.onCheck(state(TargetStatus.UNKNOWN), true, 3)
        assertEquals(SlowAlert.Action.NONE, alert.onCheck(state(), true, 4))
        alert.onCheck(state(), false, 5)
        repeat(2) { assertEquals(SlowAlert.Action.NONE, alert.onCheck(state(), true, 6L + it)) }
        assertFalse(alert.active)
    }
    @Test fun thresholdHysteresisCooldownAndChangeReset() {
        var settings = TargetAlertSettings(notifySlow = true, slowThresholdMs = 2000)
        val alert = TargetSlowAlert { settings }
        repeat(2) { alert.onCheck(state(latency = 2500), true, it.toLong()) }
        settings = settings.copy(slowThresholdMs = 1000)
        assertEquals(SlowAlert.Action.NONE, alert.onCheck(state(), true, 3))
        assertEquals(SlowAlert.Action.NONE, alert.onCheck(state(), true, 4))
        assertEquals(SlowAlert.Action.ALERT, alert.onCheck(state(), true, 5))
        assertEquals(SlowAlert.Action.NONE, alert.onCheck(state(latency = 600), true, 6))
        assertEquals(SlowAlert.Action.CLEAR, alert.onCheck(state(latency = 599), true, 7))
        repeat(3) { assertEquals(SlowAlert.Action.NONE, alert.onCheck(state(), true, 8L + it)) }
        assertEquals(SlowAlert.Action.ALERT, alert.onCheck(state(), true, SlowAlert.COOLDOWN_MS + 5))
        assertEquals(SlowAlert.Action.CLEAR, alert.onCheck(state(TargetStatus.PARTIAL), true, SlowAlert.COOLDOWN_MS + 6))
    }
    @Test fun existingPolicyAlsoBreaksStreakAtUnknownAndMissingLatency() {
        val alert = SlowAlert()
        repeat(2) { alert.onCheck("OK", 1500, it.toLong()) }
        alert.onCheck("UNKNOWN", -1, 3)
        assertEquals(SlowAlert.Action.NONE, alert.onCheck("OK", 1500, 4))
        alert.onCheck("OK", -1, 5)
        repeat(2) { assertEquals(SlowAlert.Action.NONE, alert.onCheck("OK", 1500, 6L + it)) }
    }
}
