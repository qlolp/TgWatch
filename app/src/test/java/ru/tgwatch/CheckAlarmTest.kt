package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckAlarmTest {
    private val armed = mutableListOf<Long>()
    private var disarmed = 0
    private var interactive = false
    private val alarm = CheckAlarm({ armed += it }, { disarmed++ }, { interactive }, padMs = 60_000L, minDelayMs = 5_000L)

    @Test fun screenOffSchedulesTheExactDueTime() {
        alarm.schedule(nowElapsed = 1_000L, delayMs = 30_000L)
        assertEquals(listOf(31_000L), armed)
    }

    @Test fun screenOnPadsTheAlarmSoTheInProcessTimerWinsTheRace() {
        interactive = true
        alarm.schedule(nowElapsed = 1_000L, delayMs = 30_000L)
        assertEquals(listOf(91_000L), armed)
    }

    @Test fun screenTurningOffRestoresTheExactDueTime() {
        interactive = true
        alarm.schedule(nowElapsed = 1_000L, delayMs = 30_000L)
        interactive = false
        alarm.screenOff(nowElapsed = 10_000L)
        assertEquals(listOf(91_000L, 31_000L), armed)
    }

    @Test fun overdueAlarmAfterScreenOffKeepsTheMinimumDelay() {
        interactive = true
        alarm.schedule(nowElapsed = 1_000L, delayMs = 30_000L)
        alarm.screenOff(nowElapsed = 40_000L)
        assertEquals("A check that is already due must not get a past alarm", 45_000L, armed.last())
    }

    @Test fun screenOffWithoutPaddingDoesNothing() {
        alarm.schedule(nowElapsed = 1_000L, delayMs = 30_000L)
        alarm.screenOff(nowElapsed = 2_000L)
        alarm.screenOff(nowElapsed = 3_000L)
        assertEquals(listOf(31_000L), armed)
    }

    @Test fun screenOffTightensOnlyOnce() {
        interactive = true
        alarm.schedule(nowElapsed = 0L, delayMs = 30_000L)
        alarm.screenOff(nowElapsed = 1_000L)
        alarm.screenOff(nowElapsed = 2_000L)
        assertEquals(2, armed.size)
    }

    @Test fun cancelDisarmsAndForgetsPadding() {
        interactive = true
        alarm.schedule(nowElapsed = 0L, delayMs = 30_000L)
        alarm.cancel()
        alarm.screenOff(nowElapsed = 1_000L)
        assertEquals(1, disarmed)
        assertEquals("A canceled alarm must not come back when the screen turns off", 1, armed.size)
    }

    @Test fun interactiveStateIsReadAtSchedulingTime() {
        var reads = 0
        val probe = CheckAlarm({ armed += it }, {}, { reads++; true })
        probe.schedule(0L, 10_000L)
        assertTrue(reads == 1)
    }
}
