package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMuteStateTest {
    private val wall = 1_760_000_000_000L
    private val elapsed = 50_000L
    private val boot = 7

    @Test fun elapsedTimeDeterminesExpiryDespiteWallClockChanges() {
        val store = MemoryStore()
        assertTrue(AlertMute.mute(store, elapsed, boot))
        for (clock in listOf(wall, wall - 86_400_000L, wall + 86_400_000L)) {
            assertEquals(AlertMute.DURATION_MS - 1000, AlertMute.remaining(store, clock, elapsed + 1000, boot))
        }
        assertEquals(1L, AlertMute.remaining(store, wall, elapsed + AlertMute.DURATION_MS - 1, boot))
        assertEquals(0L, AlertMute.remaining(store, wall, elapsed + AlertMute.DURATION_MS, boot))
        assertEquals(AlertMute.State(), store.state)
    }

    @Test fun processReloadUsesPersistedElapsedDeadlineWithinSameBoot() {
        val store = MemoryStore()
        AlertMute.mute(store, elapsed, boot)
        val reopened = MemoryStore(store.state)
        assertEquals(AlertMute.DURATION_MS - 5000, AlertMute.remaining(reopened, wall, elapsed + 5000, boot))
    }

    @Test fun rebootClearsEvenWhenNewElapsedTimeLooksPlausible() {
        val store = MemoryStore()
        AlertMute.mute(store, elapsed, boot)
        assertEquals(0L, AlertMute.remaining(store, wall, elapsed + 1000, boot + 1))
        assertEquals(AlertMute.State(), store.state)
        assertEquals(0L, AlertMute.remaining(store, wall, elapsed + 1000, boot))
    }

    @Test fun unknownBootFailsOpenAndDiscardsLegacyInsteadOfGuessing() {
        val store = MemoryStore(AlertMute.State(legacyUntil = wall + 60_000))
        assertEquals(0L, AlertMute.remaining(store, wall, elapsed, null))
        assertEquals(AlertMute.State(), store.state)
        assertFalse(AlertMute.mute(store, elapsed, null))
    }

    @Test fun expiredTimerNeverRevivesAfterWallRollbackOrReload() {
        val store = MemoryStore()
        AlertMute.mute(store, elapsed, boot)
        AlertMute.remaining(store, wall, elapsed + AlertMute.DURATION_MS, boot)
        assertEquals(0L, AlertMute.remaining(MemoryStore(store.state), wall - 60_000, elapsed, boot))
    }

    @Test fun cancellationPersistsAndCannotReviveAfterReload() {
        val store = MemoryStore()
        AlertMute.mute(store, elapsed, boot)
        assertTrue(AlertMute.clear(store))
        assertEquals(0L, AlertMute.remaining(MemoryStore(store.state), wall, elapsed + 1, boot))
    }

    @Test fun validLegacyDeadlineMigratesOnceWithOnlyItsRemainingTime() {
        val store = MemoryStore(AlertMute.State(legacyUntil = wall + 60_000))
        assertEquals(60_000L, AlertMute.remaining(store, wall, elapsed, boot))
        assertEquals(0L, store.state.legacyUntil)
        assertEquals(30_000L, AlertMute.remaining(MemoryStore(store.state), wall - 600_000, elapsed + 30_000, boot))
        assertEquals(0L, AlertMute.remaining(store, wall, elapsed + 60_000, boot))
        assertEquals(AlertMute.State(), store.state)
    }

    @Test fun expiredOrImplausibleLegacyDeadlinesAreDurablyDiscarded() {
        for (until in listOf(wall, wall - 1, wall + AlertMute.DURATION_MS + 1)) {
            val store = MemoryStore(AlertMute.State(legacyUntil = until))
            assertEquals(0L, AlertMute.remaining(store, wall, elapsed, boot))
            assertEquals(AlertMute.State(), store.state)
            assertEquals(0L, AlertMute.remaining(MemoryStore(store.state), wall - 60_000, elapsed, boot))
        }
    }

    @Test fun failedMuteCannotReportSuccessOrLeaveInMemoryMuteActive() {
        val store = MemoryStore().apply { writesSucceed = false }
        assertFalse(AlertMute.mute(store, elapsed, boot))
        assertEquals(0L, AlertMute.remaining(store, wall, elapsed, boot))
    }

    @Test fun failedMigrationFailsOpenAndFailedCancelReportsFailure() {
        val store = MemoryStore(AlertMute.State(legacyUntil = wall + 60_000)).apply { writesSucceed = false }
        assertEquals(0L, AlertMute.remaining(store, wall, elapsed, boot))
        assertFalse(AlertMute.clear(store))
    }

    @Test fun elapsedRollbackAndMalformedDurationFailOpen() {
        for (state in listOf(AlertMute.State(boot, elapsed, elapsed + AlertMute.DURATION_MS),
            AlertMute.State(boot, 0, elapsed + AlertMute.DURATION_MS + 1))) {
            val store = MemoryStore(state)
            assertEquals(0L, AlertMute.remaining(store, wall, elapsed - 1, boot))
            assertEquals(AlertMute.State(), store.state)
        }
    }

    @Test fun negativeBootIdentityCannotStartAMute() {
        val store = MemoryStore()
        assertFalse(AlertMute.mute(store, elapsed, -1))
        assertEquals(AlertMute.State(), store.state)
    }

    @Test fun incompletePersistedElapsedTimerIsDurablyDiscarded() {
        val store = MemoryStore(AlertMute.State(untilElapsed = elapsed + AlertMute.DURATION_MS))
        assertEquals(0L, AlertMute.remaining(store, wall, elapsed, boot))
        assertEquals(AlertMute.State(), store.state)
    }

    @Test fun overflowingLegacyDifferenceCannotBeAdopted() {
        val store = MemoryStore(AlertMute.State(legacyUntil = Long.MAX_VALUE))
        assertEquals(0L, AlertMute.remaining(store, Long.MIN_VALUE, elapsed, boot))
        assertEquals(AlertMute.State(), store.state)
    }

    private class MemoryStore(var state: AlertMute.State = AlertMute.State()) : AlertMute.Store {
        var writesSucceed = true
        override fun read(): AlertMute.State = state
        override fun write(state: AlertMute.State): Boolean {
            this.state = state
            return writesSucceed
        }
    }
}
