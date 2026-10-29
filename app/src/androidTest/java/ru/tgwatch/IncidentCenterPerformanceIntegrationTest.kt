package ru.tgwatch

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real-Android regression check, deliberately with a generous emulator budget rather than a frame-time claim. */
@RunWith(AndroidJUnit4::class)
class IncidentCenterPerformanceIntegrationTest {
    @Test fun sixtyFiveThousandRowsStayBoundedAcrossRepeatedCenterPreparation() {
        val now = 100_000_000L
        val from = now - 86_400_000L
        val rows = List(65_000) { index ->
            val at = now - 65_000L + index
            Observation(at, at + 1, if (index % 997 == 0) "TG_DOWN" else "OK", 20)
        }
        val started = SystemClock.elapsedRealtime()
        val checks = PreparedChecks(rows, rows.last().clockEpoch)
        val episodes = OutageEpisodes.derivePrepared(checks, from, now)
        val stats = Timeline.stats(checks, from, now)
        assertEquals(65_000, stats.checks)
        repeat(5) {
            val byTarget = TargetId.entries.associateWith { id ->
                episodes.map { it.copy(clockEpoch = id.ordinal.toLong()) }
            }
            val allStats = TargetId.entries.associateWith { stats }
            val snapshot = IncidentCenter.build(byTarget, emptyList(), allStats, from, now)
            assertTrue(snapshot.events.size <= IncidentCenter.MAX_EVENTS)
            assertEquals(TargetId.entries.size, snapshot.coverage.size)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        println("ANDROID_INCIDENT_CENTER_65K preparationAndFiveBuildsMs=$elapsed")
        assertTrue("Incident-center preparation took $elapsed ms", elapsed < 20_000L)
    }
}
