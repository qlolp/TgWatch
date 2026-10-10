package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TimelinePortabilityTest {
    private fun value(stats: TimeStats, name: String): Number = when (name) {
        "okChecks" -> stats.okChecks
        "downChecks" -> stats.downChecks
        "partialChecks" -> stats.partialChecks
        "offlineChecks" -> stats.offlineChecks
        "unknownChecks" -> stats.unknownChecks
        else -> throw AssertionError(name)
    }
    private fun stats(vararg rows: Observation, from: Long = 0, until: Long = 10_000) = Timeline.stats(rows.toList(), from, until)
    private fun completed(s: TimeStats) = s.completedOutageCount
    private fun mttr(s: TimeStats) = s.mttrMs

    @Test fun countsOnlyActualChecksInCurrentEpochAndWindow() {
        val rows = listOf(Observation(0, 1000, "OK", clockEpoch = 0), Observation(0, 1000, "OK", clockEpoch = 1),
            Observation(2000, 3000, "TG_DOWN", clockEpoch = 1), Observation(3000, 4000, "PARTIAL", clockEpoch = 1),
            Observation(4000, 5000, "NO_NETWORK", clockEpoch = 1), Observation(5000, 6000, "UNKNOWN", clockEpoch = 1))
        val s = Timeline.stats(rows, 0, 7000)
        assertEquals(5, s.checks)
        for (field in listOf("okChecks", "downChecks", "partialChecks", "offlineChecks", "unknownChecks")) assertEquals(field, 1, value(s, field).toInt())
        assertEquals(3000, s.unknownMs)
    }
    @Test fun completedDownEpisodesIncludePartialContinuityAndAverageTheirDurations() {
        val s = stats(Observation(0, 1000, "OK"), Observation(1000, 2000, "TG_DOWN"),
            Observation(2000, 3000, "PARTIAL"), Observation(3000, 4000, "TG_DOWN"), Observation(4000, 5000, "OK"),
            Observation(5000, 6000, "TG_DOWN"), Observation(6000, 7000, "OK"))
        assertEquals(2, completed(s))
        assertEquals(2000L, mttr(s))
    }
    @Test fun unfinishedPartialAndDownEpisodesHaveNoMttr() {
        for (kind in listOf("TG_DOWN", "PARTIAL")) {
            val s = stats(Observation(0, 1000, "OK"), Observation(1000, 2000, "TG_DOWN"), Observation(2000, 10_000, kind))
            assertEquals(0, completed(s)); assertEquals(-1L, mttr(s))
        }
    }
    @Test fun gapsOfflineAndUnknownBreakTheEpisode() {
        val ends = listOf(emptyList(), listOf(Observation(2000, 3000, "NO_NETWORK")), listOf(Observation(2000, 3000, "UNKNOWN")))
        for (middle in ends) {
            val s = Timeline.stats(listOf(Observation(0, 1000, "OK"), Observation(1000, 2000, "TG_DOWN")) + middle + Observation(3000, 4000, "OK"), 0, 5000)
            assertEquals(0, completed(s)); assertEquals(-1L, mttr(s))
        }
    }
    @Test fun unknownOnsetAndClippedWindowCannotCompleteAnEpisode() {
        val rows = listOf(Observation(0, 1000, "OK"), Observation(1000, 4000, "TG_DOWN"), Observation(4000, 5000, "OK"))
        assertEquals(0, completed(Timeline.stats(rows, 2000, 5000)))
        assertEquals(0, completed(Timeline.stats(rows, 1000, 5000)))
        assertEquals(0, completed(Timeline.stats(rows, 0, 4000)))
        assertEquals(0, completed(stats(Observation(1000, 2000, "TG_DOWN"), Observation(2000, 3000, "OK"))))
    }
    @Test fun archivedEpochAndPartialOnlyCannotProduceCompletedOutages() {
        assertEquals(0, completed(stats(Observation(0, 1000, "OK"), Observation(1000, 2000, "TG_DOWN"), Observation(2000, 3000, "OK", clockEpoch = 1))))
        assertEquals(0, completed(stats(Observation(0, 1000, "OK"), Observation(1000, 2000, "PARTIAL"), Observation(2000, 3000, "OK"))))
    }
    @Test fun largeValidLatenciesDoNotOverflowTheirAverage() {
        val s = stats(Observation(0, 1000, "OK", Long.MAX_VALUE), Observation(1000, 2000, "OK", Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, s.avgLatencyMs)
    }
}
