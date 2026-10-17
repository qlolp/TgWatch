package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetModelTest {
    @Test fun independentStatesDoNotOverwriteTelegram() {
        val repository = TargetRepository()
        val telegram = repository.configuration(TargetId.TELEGRAM)
        val matrix = repository.configure(TargetDefinition.default(TargetId.MATRIX).copy(enabled = true))
        assertTrue(repository.publish(TargetSnapshot(TargetId.TELEGRAM, TargetStatus.DOWN,
            checkedAtMs = 1_000, generation = telegram.generation, revision = 1)))
        assertTrue(repository.publish(TargetSnapshot(TargetId.MATRIX, TargetStatus.OK,
            checkedAtMs = 2_000, generation = matrix.generation, revision = 1)))
        assertEquals(TargetStatus.DOWN, repository.snapshot(TargetId.TELEGRAM)!!.status)
        assertEquals(TargetStatus.OK, repository.snapshot(TargetId.MATRIX)!!.status)
        assertFalse(repository.definitions().first { it.id == TargetId.CUSTOM }.enabled)
    }
    @Test fun lateOldEndpointResultCannotOverwriteNewConfigurationOrAnotherTarget() {
        val repository = TargetRepository()
        val first = repository.configure(TargetDefinition.default(TargetId.CUSTOM).copy(enabled = true))
        val current = repository.configure(first.definition, endpointChanged = true)
        val old = TargetSnapshot(TargetId.CUSTOM, TargetStatus.DOWN, checkedAtMs = 1_000,
            generation = first.generation, endpointRevision = first.endpointRevision, revision = 1)
        assertFalse(repository.publish(old))
        assertTrue(repository.publish(old.copy(status = TargetStatus.OK, generation = current.generation,
            endpointRevision = current.endpointRevision, revision = 2)))
        assertFalse(repository.publish(old.copy(generation = current.generation,
            endpointRevision = current.endpointRevision)))
        assertEquals(TargetStatus.OK, repository.snapshot(TargetId.CUSTOM)!!.status)
    }
    @Test fun endpointRevisionStartsNewCoverage() {
        val row = TargetObservation(Observation(0, 600_000, "OK", 50), 0)
        var revision = 0L
        val history = TargetHistory({ listOf(row) }, { revision })
        val old = history.readStats(TargetId.MATRIX, 1, 600_000).valueOrThrow()
        assertEquals(600_000L, CoverageStats.from(old, CoverageStats.DAY_MS).assessedMs)
        revision = 1
        val changed = history.readStats(TargetId.MATRIX, 1, 600_000).valueOrThrow()
        assertEquals(0L, CoverageStats.from(changed, CoverageStats.DAY_MS).assessedMs)
        assertEquals(-1.0, changed.uptimePercent, 0.0)
    }
    @Test fun invalidTargetAndIntervalRejected() {
        assertNull(TargetId.fromWire("https://secret.example"))
        assertNull(TargetId.fromWire("MATRIX"))
        assertEquals(TargetId.MATRIX, TargetId.fromWire("matrix"))
        for (interval in listOf(0, 59, 301)) assertThrows(IllegalArgumentException::class.java) {
            TargetDefinition.default(TargetId.MATRIX).copy(intervalSec = interval)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetDefinition.default(TargetId.TELEGRAM).copy(intervalSec = 121)
        }
        assertThrows(IllegalArgumentException::class.java) { TargetAlertSettings(slowThresholdMs = 550) }
        assertThrows(IllegalArgumentException::class.java) { TargetAlertSettings(slowThresholdMs = 5_100) }
        assertFalse(TargetDefinition.default(TargetId.MATRIX).alerts.notifySlow)
        assertTrue(TargetDefinition.default(TargetId.TELEGRAM).alerts.notifySlow)
    }
    @Test fun storageFailurePreservesLastKnownAggregatesWithoutInventingAnOutage() {
        var fail = false
        val history = TargetHistory({ if (fail) throw java.io.IOException("secret")
            else listOf(TargetObservation(Observation(0, 600_000, "OK"), 0)) }, { 0 })
        val first = history.readStats(TargetId.MATRIX, 1, 600_000).valueOrThrow()
        fail = true
        val failure = history.readStats(TargetId.MATRIX, 1, 600_000) as HistoryRead.Unavailable
        assertEquals(first, failure.lastKnown)
        assertEquals(HistoryFailure.READ, failure.failure)
    }
    @Test fun changedEndpointNeverReusesOldCacheAfterStorageFailure() {
        var revision = 0L
        var fail = false
        val history = TargetHistory({ if (fail) throw java.io.IOException("unavailable")
            else listOf(TargetObservation(Observation(0, 600_000, "OK"), 0)) }, { revision })
        history.readStats(TargetId.CUSTOM, 1, 600_000).valueOrThrow()
        revision = 1
        fail = true
        val failed = history.readStats(TargetId.CUSTOM, 1, 600_000) as HistoryRead.Unavailable
        assertNull("The previous endpoint's coverage is not a cache for the new endpoint", failed.lastKnown)
    }
}
