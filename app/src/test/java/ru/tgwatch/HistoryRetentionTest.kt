package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRetentionTest {
    @Test fun backupAcceptsRetentionAndRejectsUnknownValues() {
        val snapshot = TargetBackupSnapshot(1L,
            mapOf("interval_sec" to 30, "history_retention" to Retention.QUARTER.name),
            TargetId.entries.map(TargetDefinition::default),
            TargetId.entries.associateWith { emptyList() },
            emptyList())
        val decoded = TargetBackupCodec.decode(TargetBackupCodec.encode(snapshot))
        assertEquals("QUARTER", decoded.globalSettings["history_retention"])
        assertEquals(4, JSONObjectFormatVersion(TargetBackupCodec.encode(snapshot)))
        assertThrows(IllegalArgumentException::class.java) {
            TargetBackupCodec.encode(snapshot.copy(globalSettings =
                snapshot.globalSettings + ("history_retention" to "CENTURY")))
        }
    }

    @Test fun weekIsThePortableDefaultWhenKeyIsAbsent() {
        val snapshot = TargetBackupSnapshot(1L, mapOf("interval_sec" to 30),
            TargetId.entries.map(TargetDefinition::default),
            TargetId.entries.associateWith { emptyList() }, emptyList())
        val decoded = TargetBackupCodec.decode(TargetBackupCodec.encode(snapshot))
        assertFalse(decoded.globalSettings.containsKey("history_retention"))
    }

    @Test fun retentionWindowsDriveCsvAndRollupBudgets() {
        assertEquals(7, Retention.WEEK.days)
        assertEquals(30, Retention.MONTH.days)
        assertEquals(90, Retention.QUARTER.days)
        assertTrue(HistoryRollup.bounds(Retention.MONTH).expectedRows >
            HistoryRollup.bounds(Retention.WEEK).expectedRows)
        assertTrue(HistoryRollup.bounds(Retention.QUARTER).expectedRows >
            HistoryRollup.bounds(Retention.MONTH).expectedRows)
    }

    private fun JSONObjectFormatVersion(bytes: ByteArray): Int =
        org.json.JSONObject(bytes.toString(Charsets.UTF_8)).getInt("formatVersion")
}
