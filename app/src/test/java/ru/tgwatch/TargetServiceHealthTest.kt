package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetServiceHealthTest {
    @Test fun extraOnlyThreeHundredSecondGoalIsNotJudgedAgainstOldTelegramInterval() {
        val matrix = TargetDefinition.default(TargetId.MATRIX).copy(enabled = true, intervalSec = 300)
        val telegram = TargetDefinition.default(TargetId.TELEGRAM).copy(enabled = false)
        assertEquals(300, TargetServiceCommands.expectedIntervalSec(30, listOf(telegram, matrix)))
        assertFalse(ServiceHealth.isOverdue(1_000, 200_000,
            TargetServiceCommands.expectedIntervalSec(30, listOf(telegram, matrix))))
        assertTrue(ServiceHealth.isOverdue(1_000, 1_000_000,
            TargetServiceCommands.expectedIntervalSec(30, listOf(telegram, matrix))))
    }
    @Test fun telegramUsesActualProfileIntervalAndFasterEnabledGoalWins() {
        val telegram = TargetDefinition.default(TargetId.TELEGRAM)
        val matrix = TargetDefinition.default(TargetId.MATRIX).copy(enabled = true, intervalSec = 60)
        assertEquals(60, TargetServiceCommands.expectedIntervalSec(300, listOf(telegram, matrix)))
        assertEquals(15, TargetServiceCommands.expectedIntervalSec(15, listOf(telegram, matrix)))
    }
}
