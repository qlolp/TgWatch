package ru.tgwatch

import android.content.Intent
import android.os.SystemClock
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LatencyUiTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var saved: TargetBackupSnapshot
    private lateinit var preferences: Map<String, *>
    @Before fun prepare() {
        HistoryTestStorage.awaitReady()
        preferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        saved = BackupManager.snapshotTargets(ctx)
        // A portable restore starts a new generation without the local-only DC file.
        BackupManager.restoreTargets(ctx, saved)
    }
    @After fun restore() {
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (::saved.isInitialized) BackupManager.restoreTargets(ctx, saved)
        if (::preferences.isInitialized) {
            val edit = Prefs.sp(ctx).edit().clear()
            preferences.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
            } }
            check(edit.commit())
        }
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }
    @Test fun selectedWindowAndRotationShowOwnSamplesAndMissingDcIsExplicit() {
        val now = System.currentTimeMillis()
        val revision = TargetPreferences.endpointRevision(ctx, TargetId.MATRIX)
        HistorySessions.queue(ctx).exclusive {
            TargetPersistence.storage().replaceRows(TargetId.MATRIX, listOf(
                TargetObservation(Observation(now - 7_200_000, now - 7_199_000, "OK", 2000), revision),
                TargetObservation(Observation(now - 10_000, now, "OK", 10), revision)))
        }.get()
        val intent = Intent(ctx, LatencyActivity::class.java).putExtra("target_id", "matrix")
        ActivityScenario.launch<LatencyActivity>(intent).use { scenario ->
            await(scenario, ctx.getString(R.string.latency_ms, 10))
            scenario.onActivity { it.findViewById<Spinner>(R.id.latencyWindow).setSelection(LatencyWindow.WEEK.ordinal) }
            await(scenario, ctx.getString(R.string.latency_ms, 2000))
            scenario.recreate()
            await(scenario, ctx.getString(R.string.latency_ms, 2000))
            scenario.onActivity {
                assertEquals(LatencyWindow.WEEK.ordinal, it.findViewById<Spinner>(R.id.latencyWindow).selectedItemPosition)
                assertTrue(it.findViewById<TextView>(R.id.latencyMeaning).text.contains("HTTP"))
            }
        }
        ActivityScenario.launch(LatencyActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 20_000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { ready = it.findViewById<TextView>(R.id.latencyDcSummary).text ==
                    ctx.getString(R.string.latency_no_dc) }
                SystemClock.sleep(100)
            }
            assertTrue("Legacy/restore must not invent DC data", ready)
        }
    }
    @Test fun thresholdRoundTripAndPublicValidation() {
        assertFalse(TargetPreferences.setSlowThreshold(ctx, TargetId.MATRIX, 501))
        assertTrue(TargetPreferences.setSlowThreshold(ctx, TargetId.MATRIX, 5000))
        val portable = BackupManager.snapshotTargets(ctx)
        val decoded = TargetBackupCodec.decode(TargetBackupCodec.encode(portable))
        assertEquals(5000L, decoded.targets.single { it.id == TargetId.MATRIX }.alerts.slowThresholdMs)
    }
    private fun await(scenario: ActivityScenario<LatencyActivity>, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = it.findViewById<TextView>(R.id.latencySummary).text.contains(expected) }
            SystemClock.sleep(100)
        }
        assertTrue("Expected latency from the selected cohort: $expected", ready)
    }
}
