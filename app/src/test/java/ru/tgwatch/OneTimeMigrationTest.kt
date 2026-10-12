package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class OneTimeMigrationTest {
    @Test fun movesUntilFirstSuccessThenStops() {
        val migration = OneTimeMigration()
        var moves = 0
        var result = false
        repeat(2) { migration.run({ true }, { moves++; result }) }
        assertEquals(2, moves)
        result = true
        repeat(3) { migration.run({ true }, { moves++; result }) }
        assertEquals(3, moves)
    }

    @Test fun lockedUserDefersTheMoveWithoutGivingUp() {
        val migration = OneTimeMigration()
        var unlocked = false
        var moves = 0
        repeat(2) { migration.run({ unlocked }, { moves++; true }) }
        assertEquals(0, moves)
        unlocked = true
        repeat(2) { migration.run({ unlocked }, { moves++; true }) }
        assertEquals(1, moves)
    }

    @Test fun exceptionIsRetriedOnTheNextRead() {
        val migration = OneTimeMigration()
        var moves = 0
        migration.run({ true }, { moves++; throw IllegalStateException("storage busy") })
        migration.run({ true }, { moves++; true })
        migration.run({ true }, { moves++; true })
        assertEquals(2, moves)
    }
}
