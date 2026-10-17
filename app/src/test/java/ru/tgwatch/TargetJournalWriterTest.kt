package ru.tgwatch

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class TargetJournalWriterTest {
    private val id = TargetId.MATRIX
    @Test fun readingAfterAClockRollbackCreatesOnlyMetadataAndKeepsArchivedChecks() {
        val disk = mutableListOf(TargetObservation(Observation(10_000, 11_000, "DOWN")))
        val writer = TargetJournalWriter({ disk.toList() }, { _, rows -> disk.clear(); disk.addAll(rows) },
            { _, row, _ -> disk.add(row) }, { 0L }, { 0L })
        writer.prepare(id, 0, 5_000)
        val current = Timeline.currentChecks(writer.read(id).valueOrThrow().map { it.row })
        assertTrue(current.isEmpty())
        assertTrue(disk.any { it.row.isEpochMetadata && it.row.at == 5_000L })
        assertTrue(disk.any { it.row.kind == "DOWN" && it.row.at == 10_000L })
    }
    @Test fun stableChecksReadOneBoundedJournalAndKeepPhysicalCompactionBounded() {
        var reads = 0
        var replacements = 0
        val disk = mutableListOf<TargetObservation>()
        val writer = TargetJournalWriter({ reads++; disk.toList() }, { _, rows ->
            replacements++; disk.clear(); disk.addAll(rows)
        }, { _, row, _ -> disk.add(row) }, { 0L }, { 0L })
        repeat(2_100) { index -> writer.record(id, Observation(1_000L + index, 2_000L + index, "OK"), 0) }
        assertEquals("Stable monitoring must not reparse the journal on each check", 1, reads)
        assertEquals(3, replacements)
        assertEquals(2_100, disk.size)
        assertEquals(2_100, writer.read(id).valueOrThrow().size)
    }
    @Test fun failedAppendRetainsMeasuredMemoryAndNextRecordRepairsIt() {
        var failAppend = true
        val disk = mutableListOf<TargetObservation>()
        val writer = TargetJournalWriter({ disk.toList() }, { _, rows -> disk.clear(); disk.addAll(rows) },
            { _, row, _ -> if (failAppend) throw IOException("injected") else disk.add(row) }, { 0L }, { 0L })
        writer.record(id, Observation(1_000, 2_000, "OK"), 0)
        try { writer.record(id, Observation(2_000, 3_000, "DOWN"), 0); fail() } catch (_: IOException) { }
        val failed = writer.read(id) as HistoryRead.Unavailable
        assertEquals(HistoryFailure.WRITE, failed.failure)
        assertEquals(2, failed.lastKnown!!.size)
        failAppend = false
        writer.record(id, Observation(3_000, 4_000, "OK"), 0)
        assertEquals(listOf("OK", "DOWN", "OK"), writer.read(id).valueOrThrow().map { it.row.kind })
        assertEquals(3, disk.size)
    }
    @Test fun earlierClockAfterRetentionCannotResurrectExpiredDiskRows() {
        val day = CoverageStats.DAY_MS
        val disk = mutableListOf(TargetObservation(Observation(1_000, 2_000, "DOWN")))
        var replacements = 0
        val writer = TargetJournalWriter({ disk.toList() }, { _, rows ->
            replacements++; disk.clear(); disk.addAll(rows)
        }, { _, row, _ -> disk.add(row) }, { 0L }, { 0L })
        writer.record(id, Observation(6 * day, 6 * day + 1_000, "OK"), 0)
        writer.record(id, Observation(8 * day, 8 * day + 1_000, "OK"), 0)
        assertFalse(writer.read(id).valueOrThrow().any { it.row.at == 1_000L })
        writer.record(id, Observation(7 * day, 7 * day + 1_000, "OK"), 0)
        assertTrue(replacements >= 2)
        assertFalse(disk.any { it.row.at == 1_000L })
    }
}
