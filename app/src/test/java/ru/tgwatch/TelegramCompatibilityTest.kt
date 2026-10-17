package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TelegramCompatibilityTest {
    @Test fun legacyAdapterRetainsTgDownAndNoLatencySentinel() {
        val state = MonitorService.State(status = MonitorService.Status.TG_DOWN, checkedAt = 1_000,
            checkedElapsed = 200, latencyMs = -1, expectedIntervalSec = 300)
        val generic = TargetSnapshot.fromTelegram(state)
        assertEquals(TargetStatus.DOWN, generic.status)
        assertNull(generic.latencyMs)
        val legacy = generic.toLegacyTelegram()
        assertEquals("TG_DOWN", legacy.status.name)
        assertEquals(-1L, legacy.latencyMs)
        assertEquals(300, legacy.expectedIntervalSec)
        assertEquals(0L, generic.withoutElapsedClock().checkedElapsedMs)
    }
    @Test fun timelineAcceptsGenericDownWithoutChangingLegacyStatistics() {
        val before = listOf(Observation(0, 60_000, "OK", 100),
            Observation(60_000, 120_000, "TG_DOWN"), Observation(120_000, 180_000, "OK", 200))
        val generic = before.map { it.copy(kind = if (it.kind == "TG_DOWN") "DOWN" else it.kind) }
        assertEquals(Timeline.stats(before, 0, 180_000), Timeline.stats(generic, 0, 180_000))
        assertEquals(Timeline.recovery(before, 120_000, false), Timeline.recovery(generic, 120_000, false))
        assertEquals(OutageEpisodes.derive(before, 0, 180_000), OutageEpisodes.derive(generic, 0, 180_000))
        val archive = generic + Observation(180_000, 240_000, "OK", clockEpoch = 1)
        val legacyCsv = Timeline.csv(archive, 0, 240_000)
        assertTrue(legacyCsv.contains(",TG_DOWN,"))
        assertFalse(legacyCsv.contains(",DOWN,"))
    }
    @Test fun nonTelegramSnapshotCannotBeReturnedByLegacyAdapter() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetSnapshot(TargetId.MATRIX).toLegacyTelegram()
        }
    }
}
