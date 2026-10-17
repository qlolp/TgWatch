package ru.tgwatch

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DcLatencyStorageTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun durableRoundTripAndFailedReplacePreservesOldFile() {
        val file = File(directory.root, "dc.csv")
        val buckets = DcLatencyBuckets()
        buckets.add("dc2-v6", 3, 2, 900001, ProbeMeasurement.SUCCESS, Long.MAX_VALUE)
        val rows = buckets.snapshot()
        DcLatencyStorage.write(file, rows)
        assertEquals(rows, DcLatencyStorage.read(file))
        val failing = object : HistoryFileOperations by SystemHistoryFileOperations {
            override fun replace(source: File, destination: File) { throw IOException("replace") }
        }
        assertThrows(IOException::class.java) { DcLatencyStorage.write(file, emptyList(), failing) }
        assertEquals(rows, DcLatencyStorage.read(file))
        file.writeText("corrupt")
        assertThrows(IOException::class.java) { DcLatencyStorage.read(file) }
    }
}
