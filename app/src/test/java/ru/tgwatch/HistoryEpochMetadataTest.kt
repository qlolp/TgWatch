package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HistoryEpochMetadataTest {
    @Test fun rollbackWithoutAnotherProbeNeverAddsACheckToLaterStatistics() {
        val rolled = Timeline.clockRollback(listOf(Observation(5000, 6000, "OK")), 1000)
        assertEquals(0, Timeline.stats(rolled, 0, 2000).checks)
        assertEquals(0, Timeline.stats(rolled, 0, 2000).unknownChecks)
        assertEquals(2000L, Timeline.stats(rolled, 0, 2000).unknownMs)
        assertEquals(rolled.last().at, rolled.last().until)
    }

    @Test fun genuineOneMillisecondUnknownIsStillAPerformedCheck() {
        val rolled = Timeline.clockRollback(listOf(Observation(5000, 6000, "OK")), 1000)
        val rows = rolled + Observation(1500, 1501, "UNKNOWN", clockEpoch = rolled.last().clockEpoch)
        val stats = Timeline.stats(rows, 0, 2000)
        assertEquals(1, stats.checks)
        assertEquals(1, stats.unknownChecks)
    }

    @Test fun realProbeAtTheBoundaryCountsOnceInEitherRowOrder() {
        val rolled = Timeline.clockRollback(listOf(Observation(5000, 6000, "OK")), 1000)
        val real = Observation(1000, 1500, "OK", 12, rolled.last().clockEpoch)
        for (rows in listOf(rolled + real, listOf(real) + rolled)) {
            val stats = Timeline.stats(rows, 0, 2000)
            assertEquals(1, stats.checks)
            assertEquals(1, stats.okChecks)
            assertEquals(500L, stats.okMs)
            assertEquals(real, Timeline.current(Timeline.retained(rows, 0)).single())
        }
    }

    @Test fun markerOnlyEpochSurvivesRetentionDiskAndEncryptedPortableRoundTrip() {
        val rolled = Timeline.clockRollback(listOf(Observation(5000, 6000, "OK")), 1000)
        val retained = Timeline.retained(rolled, 2000)
        assertEquals(1L, Timeline.current(retained).single().clockEpoch)
        assertEquals(0, Timeline.stats(retained, 0, 2000).checks)
        val dir = Files.createTempDirectory("epoch-metadata").toFile()
        try {
            val file = File(dir, "history.csv")
            val saved = HistoryStorage.replace(file, retained, 2000)
            assertEquals(saved, HistoryStorage.read(file))
            val snapshot = BackupSnapshot(2000, emptyMap(), saved, emptyList())
            assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)))
            val password = "epoch-metadata-password".toCharArray()
            assertEquals(snapshot, PortableBackupCodec.decrypt(PortableBackupCodec.encrypt(snapshot, password), password))
            assertEquals(0, Timeline.stats(HistoryStorage.read(file), 0, 2000).checks)
        } finally { dir.deleteRecursively() }
    }

    @Test fun csvRoundTripRetainsTheMarkerOnlyEpochAndItsArchives() {
        val rolled = Timeline.clockRollback(listOf(Observation(5000, 6000, "OK")), 1000)
        val imported = CsvImport.parse(Timeline.csv(rolled, 0, 2000))
        assertTrue(imported.any { it.clockEpoch == 0L && it.kind == "OK" })
        assertEquals(1L, Timeline.current(imported).single().clockEpoch)
        assertEquals(0, Timeline.stats(imported, 0, 2000).checks)
        assertEquals(0L, Timeline.stats(imported, 0, 2000).okMs)
    }

    @Test fun onlyUnknownWithNoLatencyMayHaveZeroWidth() {
        val dir = Files.createTempDirectory("epoch-validation").toFile()
        try {
            val file = File(dir, "history.csv")
            val marker = Observation(1000, 1000, "UNKNOWN", clockEpoch = 1)
            assertEquals(listOf(marker), HistoryStorage.replace(file, listOf(marker), 0))
            for (invalid in listOf(marker.copy(kind = "OK"), marker.copy(latencyMs = 0), marker.copy(until = 999))) {
                try { HistoryStorage.replace(file, listOf(invalid), 0); fail("Invalid metadata accepted") }
                catch (_: IllegalArgumentException) { }
                assertEquals(listOf(marker), HistoryStorage.read(file))
                try { BackupCodec.encode(BackupSnapshot(2000, emptyMap(), listOf(invalid), emptyList())); fail("Invalid backup metadata accepted") }
                catch (_: IllegalArgumentException) { }
            }
        } finally { dir.deleteRecursively() }
    }
}
