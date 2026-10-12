package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class OutageEpisodesTest {
    private fun row(at: Long, until: Long, kind: String, epoch: Long = 0) =
        Observation(at, until, kind, clockEpoch = epoch)

    @Test fun gapAndOfflineStaySeparateUntilObservedRecovery() {
        val rows = listOf(row(0, 10, "OK"), row(10, 20, "TG_DOWN"), row(30, 40, "NO_NETWORK"),
            row(40, 50, "PARTIAL"), row(50, 60, "OK"))
        val episode = OutageEpisodes.derive(rows, 0, 60).single()
        assertEquals(OutageEpisode(10, 50, true, false, 10, 10, 10, 10, 0), episode)
    }

    @Test fun unobservedRecoveryHasUnknownTailAndNeverAddsOutageDuration() {
        val episode = OutageEpisodes.derive(listOf(row(10, 20, "TG_DOWN")), 0, 50).single()
        assertFalse(episode.recovered)
        assertTrue(episode.startCensored)
        assertEquals(50L, episode.endedAt)
        assertEquals(10L, episode.downMs)
        assertEquals(30L, episode.unknownMs)
    }

    @Test fun partialStartsEpisodeAndWindowClippingCensorsOnset() {
        val rows = listOf(row(0, 10, "OK"), row(10, 30, "PARTIAL"), row(30, 40, "OK"))
        assertEquals(OutageEpisode(10, 30, true, false, 0, 20, 0, 0, 0),
            OutageEpisodes.derive(rows, 0, 40).single())
        assertEquals(OutageEpisode(20, 30, true, true, 0, 10, 0, 0, 0),
            OutageEpisodes.derive(rows, 20, 40).single())
        assertFalse(OutageEpisodes.derive(rows, 0, 30).single().recovered)
    }

    @Test fun onlyCurrentEpochAndLatestHundredAreReturned() {
        val rows = mutableListOf(row(0, 1000, "TG_DOWN"))
        repeat(105) { index ->
            val at = index * 30L
            rows += row(at, at + 10, "OK", 1)
            rows += row(at + 10, at + 20, "TG_DOWN", 1)
            rows += row(at + 20, at + 30, "OK", 1)
        }
        val episodes = OutageEpisodes.derive(rows, 0, 4000)
        assertEquals(100, episodes.size)
        assertEquals(3130L, episodes.first().startedAt)
        assertEquals(160L, episodes.last().startedAt)
        assertTrue(episodes.all { it.clockEpoch == 1L && it.recovered })
    }

    @Test fun sameTimeRealResultWinsAndMetadataCreatesNoIncident() {
        val rows = listOf(row(10, 20, "TG_DOWN"), row(10, 20, "OK"), row(10, 10, "UNKNOWN"))
        assertTrue(OutageEpisodes.derive(rows, 0, 30).isEmpty())
        assertTrue(OutageEpisodes.derive(rows + row(5, 5, "UNKNOWN", 1), 0, 30).isEmpty())
    }

    @Test fun offlineBeforeFailureCensorsStartAndEmptyWindowsHaveNoEpisodes() {
        val rows = listOf(row(0, 10, "NO_NETWORK"), row(10, 20, "TG_DOWN"), row(20, 30, "OK"))
        assertTrue(OutageEpisodes.derive(rows, 0, 30).single().startCensored)
        assertTrue(OutageEpisodes.derive(rows, 30, 30).isEmpty())
    }
}
