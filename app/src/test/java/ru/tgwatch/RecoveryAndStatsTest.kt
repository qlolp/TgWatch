package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class RecoveryAndStatsTest {
    @Test fun expiredCurrentEpochRemainsArchivedAfterRepeatedRetentionAndStorageReload() {
        val rows = listOf(Observation(10_000,20_000,"OK",clockEpoch=0),
            Observation(20_000,30_000,"TG_DOWN",clockEpoch=0),
            Observation(30_000,40_000,"OK",clockEpoch=0),Observation(0,1000,"OK",clockEpoch=1))
        val retained = Timeline.retained(rows,10_000)
        assertEquals(1L,Timeline.current(retained).single().clockEpoch)
        assertEquals("UNKNOWN",Timeline.current(retained).single().kind)
        assertEquals(0L,Timeline.stats(retained,10_000,40_000).downMs)
        assertEquals(0,Timeline.stats(retained,10_000,40_000).completedOutages)
        val file = java.io.File.createTempFile("retention",".csv")
        try {
            HistoryStorage.write(file,retained)
            val reloaded = Timeline.retained(HistoryStorage.read(file),20_000)
            assertEquals(1L,Timeline.current(reloaded).single().clockEpoch)
            assertEquals(0,Timeline.stats(reloaded,20_000,40_000).checks)
        } finally { file.delete() }
    }
    @Test fun storageLimitAndDuplicateTimestampsPreserveTheCurrentEpoch() {
        val archived = (1..10).map { Observation(it*1000L,it*1000L+500,"TG_DOWN",clockEpoch=0) }
        val rows = archived + Observation(0,100,"OK",clockEpoch=1) + Observation(0,100,"TG_DOWN",clockEpoch=1)
        val retained = Timeline.retained(rows,200,3)
        assertEquals(3,retained.size)
        assertEquals(1L,Timeline.current(retained).single().clockEpoch)
        assertEquals("UNKNOWN",Timeline.current(retained).single().kind)
        assertEquals(0L,Timeline.stats(retained,200,20_000).downMs)
        val fresh = Timeline.retained(retained + Observation(500,1000,"OK",clockEpoch=1),200,3)
        assertEquals(listOf(Observation(500,1000,"OK",clockEpoch=1)),Timeline.current(fresh))
    }
    @Test fun repeatedFailuresCountAsOneEpisodeAndLatestDurationIsSeparate() {
        val rows = listOf(Observation(0, 1000, "TG_DOWN"), Observation(1000, 2000, "TG_DOWN"),
            Observation(2000, 3000, "OK"), Observation(3000, 3500, "TG_DOWN"), Observation(3500, 4000, "OK"))
        val stats = Timeline.stats(rows, 0, 4000)
        assertEquals(2, stats.outageCount)
        assertEquals(500L, stats.lastOutageMs)
        assertEquals(2000L, stats.longestOutageMs)
        assertFalse(stats.lastOutageOngoing)
    }
    @Test fun gapsAndOfflineAreNotInventedTelegramOutages() {
        val stats = Timeline.stats(listOf(Observation(0, 1000, "TG_DOWN"),
            Observation(2000, 3000, "TG_DOWN"), Observation(3000, 4000, "NO_NETWORK")), 0, 4000)
        assertEquals(2, stats.outageCount)
        assertEquals(1, stats.offlineCount)
        assertEquals(1000L, stats.lastOutageMs)
        assertFalse(stats.lastOutageOngoing)
        assertEquals(1000L, stats.unknownMs)
    }
    @Test fun ongoingOutageIsClippedToSelectedWindow() {
        val stats = Timeline.stats(listOf(Observation(0, 5000, "TG_DOWN")), 2000, 4000)
        assertEquals(1, stats.outageCount)
        assertEquals(2000L, stats.lastOutageMs)
        assertTrue(stats.lastOutageOngoing)
    }
    @Test fun recoverySurvivesPartialIntermediateStateAndEmitsOnce() {
        val rows = listOf(Observation(0, 1000, "TG_DOWN"), Observation(1000, 2000, "PARTIAL"),
            Observation(2000, 3000, "OK"))
        assertEquals(2000L, Timeline.recovery(rows, 2000, false)?.durationMs)
        assertNull(Timeline.recovery(rows + Observation(3000, 4000, "OK"), 3000, false))
    }
    @Test fun partialOnlyRecoveryRequiresOptInAndOfflineRecoveryDoesNot() {
        val partial = listOf(Observation(0, 1000, "PARTIAL"), Observation(1000, 2000, "OK"))
        assertNull(Timeline.recovery(partial, 1000, false))
        assertEquals(1000L, Timeline.recovery(partial, 1000, true)?.durationMs)
        val offline = listOf(Observation(0, 1000, "NO_NETWORK"), Observation(1000, 2000, "OK"))
        assertEquals(1000L, Timeline.recovery(offline, 1000, false)?.durationMs)
    }
    @Test fun expiredObservationAndUnknownDoNotClaimRecoveryAcrossGaps() {
        assertNull(Timeline.recovery(listOf(Observation(0, 1000, "TG_DOWN"),
            Observation(2000, 3000, "OK")), 2000, true))
        assertNull(Timeline.recovery(listOf(Observation(0, 1000, "TG_DOWN"),
            Observation(1000, 2000, "UNKNOWN"), Observation(2000, 3000, "OK")), 2000, true))
    }
    @Test fun archivedClockEpochCannotCreateCurrentRecoveryOrIncidentCount() {
        val rows = listOf(Observation(0, 1000, "TG_DOWN", clockEpoch = 0),
            Observation(500, 1500, "OK", clockEpoch = 1))
        assertNull(Timeline.recovery(rows, 500, true))
        assertEquals(0, Timeline.stats(rows, 0, 1500).outageCount)
    }
    @Test fun durationInRecoveryKeepsSeconds() {
        assertEquals("17 мин 24 с", recoveryDurationStr(1_044_000))
        assertEquals("1 ч 2 мин 3 с", recoveryDurationStr(3_723_000))
    }
}
