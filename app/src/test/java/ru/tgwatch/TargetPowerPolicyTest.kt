package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetPowerPolicyTest {
    private val awake = TargetPowerState(true, false, 80, false)

    @Test fun disabledOptionKeepsConfiguredIntervalAcrossRestrictions() {
        assertEquals(60, TargetPowerPolicy.intervalSec(60, false, awake.copy(screenOn = false, powerSave = true)))
    }
    @Test fun screenOffUsesFiveMinutesWithoutShorteningConfiguration() {
        assertEquals(300, TargetPowerPolicy.intervalSec(60, true, awake.copy(screenOn = false)))
        assertEquals(120, TargetPowerPolicy.intervalSec(120, true, awake))
    }
    @Test fun lowChargeBoundaryAndChargingAreIndependentOfSystemSaving() {
        assertEquals(600, TargetPowerPolicy.intervalSec(60, true, awake.copy(batteryPercent = 15)))
        assertEquals(60, TargetPowerPolicy.intervalSec(60, true, awake.copy(batteryPercent = 16)))
        assertEquals(60, TargetPowerPolicy.intervalSec(60, true, awake.copy(batteryPercent = 15, charging = true)))
        assertEquals(600, TargetPowerPolicy.intervalSec(60, true, awake.copy(charging = true, powerSave = true)))
        assertEquals(60, TargetPowerPolicy.intervalSec(60, true, awake.copy(batteryPercent = null)))
    }
    @Test fun changingIntervalKeepsPhysicalAdmissionAndOneManualFollowUp() {
        val id = TargetId.MATRIX
        val schedule = TargetSchedule(listOf(TargetDefinition.default(id).copy(enabled = true)))
        assertEquals(listOf(id), schedule.due(0))
        schedule.setInterval(id, 600, 1)
        assertTrue(schedule.due(1).isEmpty())
        assertTrue(schedule.request(id, 2))
        assertFalse(schedule.request(id, 3))
        schedule.finished(id, 100)
        assertEquals(listOf(id), schedule.due(100))
        schedule.finished(id, 200)
        assertEquals(600_200L, schedule.nextWakeElapsed())
        schedule.setInterval(id, 60, 300)
        assertEquals(60_200L, schedule.nextWakeElapsed())
        assertTrue(schedule.due(60_199).isEmpty())
        assertEquals(listOf(id), schedule.due(60_200))
    }
    @Test fun restrictedAttemptPublishesItsIntervalWithoutWideningOlderCoverage() {
        val id = TargetId.MATRIX
        val repository = TargetRepository(wallClock = { 1_000 }, elapsedClock = { 1_000 })
        val config = repository.configure(TargetDefinition.default(id).copy(enabled = true))
        val previous = TargetSnapshot(id, TargetStatus.OK, 500, 500, 60, generation = config.generation)
        assertTrue(repository.publish(previous))
        val attempt = TargetAttempt(id, config.generation, 0, 0, 700, expectedIntervalSec = 600)
        val report = TargetReport(TargetStatus.OK, emptyList(), true, 20)
        assertTrue(repository.publish(attempt, report))
        val current = repository.snapshot(id)!!
        assertEquals(600, current.expectedIntervalSec)
        assertEquals(60, previous.expectedIntervalSec)
        val observation = TargetWebhookObservation.from(current, report, 0, 601_000, 601_000)
        assertEquals(600, observation.expectedIntervalSec)
        assertFalse(observation.stale)
        assertEquals(300, TargetDefinition.MAX_TARGET_SEC)
    }
}
