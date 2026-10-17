package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetCsvTest {
    private val row = Observation(1_000, 2_000, "DOWN", 40)
    @Test fun roundTripKeepsGoalAndEndpointIdentityWithoutMixingStatistics() {
        val original = linkedMapOf(TargetId.MATRIX to listOf(TargetObservation(row, 2),
            TargetObservation(row.copy(at = 3_000, until = 4_000, kind = "OK"), 3)),
            TargetId.WHATSAPP to listOf(TargetObservation(row.copy(kind = "OK"), 0)))
        val csv = TargetCsv.encode(original, 0, 5_000)
        assertTrue(csv.lineSequence().first().endsWith("target_id,endpoint_revision"))
        val parsed = TargetCsv.parse(csv)
        assertEquals(original.keys, parsed.keys)
        assertEquals(original[TargetId.MATRIX], parsed[TargetId.MATRIX])
        assertEquals(original[TargetId.WHATSAPP], parsed[TargetId.WHATSAPP])
        assertFalse(csv.contains("https://"))
        assertTrue(csv.contains(",matrix,2"))
    }
    @Test fun legacyCsvStillImportsIntoTelegramOnly() {
        val legacy = Timeline.csv(listOf(row.copy(kind = "TG_DOWN")), 1_000, 2_000)
        val parsed = TargetCsv.parse(legacy)
        assertEquals(setOf(TargetId.TELEGRAM), parsed.keys)
        assertEquals("DOWN", parsed[TargetId.TELEGRAM]!!.single().row.kind)
        assertEquals(0L, parsed[TargetId.TELEGRAM]!!.single().endpointRevision)
    }
    @Test fun rejectsUnknownTargetsEndpointOverflowAndInconsistentCurrentFlag() {
        val valid = TargetCsv.encode(mapOf(TargetId.MATRIX to listOf(TargetObservation(row, 2))), 1_000, 2_000)
        for (bad in listOf(valid.replace(",matrix,", ",secret,"),
            valid.replace(",matrix,2", ",matrix,9223372036854775808"),
            valid.replace(",true,", ",false,"))) {
            assertThrows(IllegalArgumentException::class.java) { TargetCsv.parse(bad) }
        }
    }
    @Test fun appliesSmallerQuotaToEachSecondaryGoal() {
        val csv = TargetCsv.encode(mapOf(TargetId.MATRIX to listOf(TargetObservation(row))), 1_000, 2_000)
        val lines = csv.trimEnd().lines()
        val excessive = lines.first() + "\n" + (lines.last() + "\n").repeat(11_001)
        assertThrows(IllegalArgumentException::class.java) { TargetCsv.parse(excessive) }
    }
}
