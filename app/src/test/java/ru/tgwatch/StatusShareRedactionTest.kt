package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class StatusShareRedactionTest {
    @Test fun immutablePublicSnapshotCannotCarryPrivateStrings() {
        val source = mutableListOf(TargetStatus.OK, TargetStatus.UNKNOWN)
        val snapshot = StatusShareSnapshot(TargetId.CUSTOM, TargetStatus.OK, 1000, 1000,
            180, false, true, 20, null, source, 7)
        val preview = StatusCardRenderer.text(snapshot)
        source[0] = TargetStatus.DOWN
        assertEquals(listOf(TargetStatus.OK, TargetStatus.UNKNOWN), snapshot.chart)
        assertEquals(preview, StatusCardRenderer.text(snapshot))
        val privateFixtures = listOf("https://private.example/token", "192.0.2.10", "Bearer abc", "my-secret-ssid")
        for (fixture in privateFixtures) {
            assertFalse(preview.contains(fixture))
            assertFalse(StatusCardRenderer.labels(snapshot).any { it.contains(fixture) })
        }
        assertFalse(StatusShareSnapshot::class.java.declaredFields.any { it.type == String::class.java })
    }
    @Test fun stoppedNoHistoryIsExplicitAndHasNoInventedCoverage() {
        val snapshot = StatusShareSnapshot(TargetId.TELEGRAM, TargetStatus.UNKNOWN, 1000, 0,
            0, true, false, null, null, emptyList(), 0)
        val text = StatusCardRenderer.text(snapshot)
        assertTrue(text.contains(Lang.t("Мониторинг остановлен", "Monitoring stopped")))
        assertTrue(text.contains(Lang.t("Нет данных истории", "No history data")))
        assertFalse(text.contains("100%"))
        assertFalse(text.contains("0%"))
    }
}
