package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class CsvImportTest {
    private val header = "from_utc,until_utc,status,duration_ms,latency_ms,clock_epoch,in_current_statistics\n"
    private fun parse(text: String): List<Observation> = CsvImport.parse(text)
    private fun rejects(text: String) { try { parse(text); fail("Accepted invalid CSV") } catch (_: IllegalArgumentException) {} }
    @Test fun currentExportRoundTripsArchivedEpochAndSkipsGeneratedUnknownChecks() {
        val rows = listOf(Observation(0, 1000, "OK", 12, 1), Observation(1000, 2000, "TG_DOWN", clockEpoch = 0))
        assertEquals(rows, parse(Timeline.csv(rows, 0, 3_000_000)))
        assertEquals(1, Timeline.stats(parse(Timeline.csv(rows, 0, 3_000_000)), 0, 3_000_000).checks)
    }
    @Test fun legacyUtcHeaderAndCrLfRemainReadable() {
        val csv = "from_utc,until_utc,status,duration_ms,latency_ms\r\n1970-01-01T00:00:00.000Z,1970-01-01T00:00:01.000Z,OK,1000,8\r\n"
        assertEquals(listOf(Observation(0, 1000, "OK", 8)), parse(csv))
    }
    @Test fun unknownOnlyCurrentEpochDoesNotReviveArchivedChecks() {
        val rows = listOf(Observation(0, 1000, "OK", clockEpoch = 0), Observation(0, 1000, "UNKNOWN", clockEpoch = 1))
        val imported = parse(Timeline.csv(rows, 0, 2000))
        assertEquals(0, Timeline.stats(imported, 0, 2000).checks)
        assertEquals(0L, Timeline.stats(imported, 0, 2000).okMs)
    }
    @Test fun datesAndDurationsMustBeExact() {
        for (row in listOf(
            "2026-02-30T00:00:00.000Z,2026-03-01T00:00:01.000Z,OK,1000,1,0,true",
            "1970-01-01T00:00:00.000Zjunk,1970-01-01T00:00:01.000Z,OK,1000,1,0,true",
            "1970-01-01T00:00:00.000Z,1970-01-01T00:00:01.000Z,OK,999,1,0,true",
            "1970-01-01T00:00:00.000Z,1970-01-01T00:10:01.000Z,OK,601000,1,0,true",
            "1969-12-31T23:59:59.000Z,1970-01-01T00:00:00.000Z,OK,1000,1,0,true")) rejects(header + row)
    }
    @Test fun rejectUnknownKindsEpochsMalformedNumbersAndPartialRecords() {
        val row = "1970-01-01T00:00:00.000Z,1970-01-01T00:00:01.000Z,OK,1000,1,0,true"
        for (bad in listOf(row.replace(",OK,", ",FAIL,"), row.replace(",0,true", ",-1,true"),
            row.replace(",1,0,", ",-2,0,"), row.replace(",1000,", ",1e3,"),
            row.replace(",1,0,", ",9223372036854775808,0,"), row.replace(",true", ",false"),
            row.substringBeforeLast(','), row + ",extra")) rejects(header + bad)
        rejects("wrong_header\n" + row)
    }
    @Test fun limitsApplyBeforeUnknownRowsAreDropped() {
        val row = "1970-01-01T00:00:00.000Z,1970-01-01T00:00:01.000Z,UNKNOWN,1000,,0,true\n"
        rejects(header + row.repeat(65_001))
        rejects(header + "x".repeat(8 * 1024 * 1024))
    }
}
