package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetScheduleTest {
    private fun enabled(id: TargetId) = TargetDefinition.default(id).copy(enabled = true)

    @Test fun roundRobinDoesNotStarveLiveTarget() {
        val ids = listOf(TargetId.MATRIX, TargetId.WHATSAPP, TargetId.CUSTOM)
        val schedule = TargetSchedule(ids.map(::enabled))
        val first = schedule.due(0)
        assertEquals(ids.toSet(), first.toSet())
        assertTrue(schedule.due(0).isEmpty())
        first.forEach { schedule.finished(it, 0) }
        val second = schedule.due(60_000)
        assertEquals(first.drop(1) + first.first(), second)
    }
    @Test fun repeatedManualRequestsKeepOnlyOneFollowUp() {
        val schedule = TargetSchedule(listOf(enabled(TargetId.MATRIX)))
        assertEquals(listOf(TargetId.MATRIX), schedule.due(0))
        assertTrue(schedule.request(TargetId.MATRIX, 1))
        repeat(1_000) { assertFalse(schedule.request(TargetId.MATRIX, 2)) }
        schedule.finished(TargetId.MATRIX, 3)
        assertEquals(listOf(TargetId.MATRIX), schedule.due(3))
        schedule.finished(TargetId.MATRIX, 4)
        assertEquals(60_004L, schedule.nextWakeElapsed())
        assertTrue(schedule.due(5).isEmpty())
    }
    @Test fun disabledTargetIsNotDueAndNextAlarmIsTheClosestTarget() {
        val schedule = TargetSchedule(listOf(enabled(TargetId.MATRIX),
            TargetDefinition.default(TargetId.WHATSAPP)))
        assertFalse(schedule.request(TargetId.WHATSAPP, 0))
        assertEquals(listOf(TargetId.MATRIX), schedule.due(0))
        assertNull(schedule.nextWakeElapsed())
        schedule.finished(TargetId.MATRIX, 100)
        assertEquals(60_100L, schedule.nextWakeElapsed())
        assertTrue(schedule.request(TargetId.MATRIX, 500))
        assertFalse(schedule.request(TargetId.MATRIX, 501))
        assertEquals(500L, schedule.nextWakeElapsed())
    }
    @Test fun disableDuringFlightDiscardsQueuedManualRequest() {
        val enabled = enabled(TargetId.MATRIX)
        val schedule = TargetSchedule(listOf(enabled))
        schedule.due(0)
        assertTrue(schedule.request(TargetId.MATRIX, 1))
        schedule.configure(enabled.copy(enabled = false), 2)
        schedule.finished(TargetId.MATRIX, 3)
        assertTrue(schedule.due(60_000).isEmpty())
        assertNull(schedule.nextWakeElapsed())
    }
    @Test fun closestAlarmCanBelongToSecondaryWithoutMovingTelegramDeadline() {
        assertEquals(10_000L, CheckAlarm.nextDelay(10_000, 40_000, 20_000))
        assertEquals(30_000L, CheckAlarm.nextDelay(10_000, 40_000, null))
        assertEquals(0L, CheckAlarm.nextDelay(10_000, 5_000, 20_000))
        assertNull(CheckAlarm.nextDelay(10_000, null, null))
    }

}
