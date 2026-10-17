package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetServiceCommandsTest {
    @Test fun legacyStopSelectsTelegramAndKeepsOtherTargetIntent() {
        val command = TargetServiceCommands.route(MonitorService.ACTION_STOP, null,
            setOf(TargetId.TELEGRAM, TargetId.MATRIX))
        assertEquals(TargetId.TELEGRAM, command.targetId)
        assertTrue(command.stopTarget)
        assertFalse(command.stopAll)
        assertEquals(MonitorService.ACTION_SETTINGS, command.action)
    }
    @Test fun taggedStopSelectsOnlyThatGoalAndLastGoalStopsTheService() {
        val command = TargetServiceCommands.route(MonitorService.ACTION_STOP, "matrix", setOf(TargetId.MATRIX))
        assertEquals(TargetId.MATRIX, command.targetId)
        assertTrue(command.stopAll)
        assertEquals(MonitorService.ACTION_STOP, command.action)
    }
    @Test fun legacyCheckCannotCheckAnEnabledMatrixWhenTelegramIsOff() {
        val command = TargetServiceCommands.route(MonitorService.ACTION_CHECK_NOW, null, setOf(TargetId.MATRIX))
        assertEquals(TargetId.TELEGRAM, command.targetId)
        assertNull(command.action)
        assertFalse(command.stopTarget)
    }
    @Test fun malformedTargetCannotTurnIntoATelegramStop() {
        val command = TargetServiceCommands.route(MonitorService.ACTION_STOP, "untrusted", TargetId.entries.toSet())
        assertTrue(command.ignored)
        assertFalse(command.stopTarget)
        assertFalse(command.stopAll)
        assertNull(command.action)
    }
}
