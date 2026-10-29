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
        val now = 65_000_000L
        val rows = List(65_000) { index ->
            val at = now - 65_000L + index
            Observation(at, at + 1, if (index % 997 == 0) "TG_DOWN" else "OK", 20)
        }
        val started = SystemClock.elapsedRealtime()
        repeat(5) {
            val episodes = TargetId.entries.associateWith { id ->
                OutageEpisodes.derive(rows, now - 86_400_000L, now).map {
                    it.copy(clockEpoch = id.ordinal.toLong())
                }
            }
            val stats = TargetId.entries.associateWith { Timeline.stats(rows, now - 86_400_000L, now) }
            val snapshot = IncidentCenter.build(episodes, emptyList(), stats, now - 86_400_000L, now)
            assertTrue(snapshot.events.size <= IncidentCenter.MAX_EVENTS)
            assertEquals(TargetId.entries.size, snapshot.coverage.size)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        println("ANDROID_INCIDENT_CENTER_65K fiveIterationsMs=$elapsed")
        assertTrue("Incident-center preparation took $elapsed ms", elapsed < 20_000L)
    }
}
