package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetEndpointRevisionTest {
    @Test fun addressChangeKeepsArchivesButStartsUnmeasuredCurrentCoverage() {
        val old = listOf(TargetObservation(Observation(1000, 2000, "OK", 10, 7), 4))
        val changed = TargetEndpointRevision.change(old, 4, 3000, 11_000)
        assertEquals(5L, changed.revision)
        assertTrue(changed.observations.containsAll(old))
        val current = changed.observations.filter { it.endpointRevision == changed.revision }.map { it.row }
        assertEquals(7L, current.single().clockEpoch)
        assertTrue(current.single().isEpochMetadata)
        assertEquals(0, Timeline.stats(current, 0, 4000).checks)
        assertEquals(0L, Timeline.stats(current, 0, 4000).okMs)
    }

    @Test fun endpointAndClockCountersAtLongMaxRebaseWithoutLosingTheCurrentIdentity() {
        val old = listOf(TargetObservation(Observation(5000, 6000, "OK", 10, Long.MAX_VALUE), Long.MAX_VALUE),
            TargetObservation(Observation(1000, 2000, "DOWN", 5, Long.MAX_VALUE), 0))
        val changed = TargetEndpointRevision.change(old, Long.MAX_VALUE, 4000, 11_000)
        assertTrue(changed.revision in 1 until Long.MAX_VALUE)
        assertEquals(changed.revision, changed.observations.maxOf { it.endpointRevision })
        val current = changed.observations.filter { it.endpointRevision == changed.revision }.map { it.row }
        assertEquals(1L, current.single().clockEpoch)
        assertEquals(0, Timeline.stats(current, 0, 7000).checks)
        assertTrue(changed.observations.any { it.row.kind == "OK" && it.row.at == 5000L })
    }

    @Test fun currentMarkerSurvivesTheRowCapAndDoesNotClaimACheck() {
        val old = (1L..4L).map { TargetObservation(Observation(it, it + 1, "OK", 10), 0) }
        val changed = TargetEndpointRevision.change(old, 0, 10, 4)
        assertEquals(4, changed.observations.size)
        assertTrue(changed.observations.last().row.isEpochMetadata)
        assertEquals(1L, changed.observations.last().endpointRevision)
    }
}
