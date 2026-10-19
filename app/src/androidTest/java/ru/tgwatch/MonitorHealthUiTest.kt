package ru.tgwatch

import android.content.SharedPreferences
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorHealthUiTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preferences: Map<String, *>
    private lateinit var original: List<Observation>
    private lateinit var oldState: MonitorService.State
    private val historyFile get() = HistoryTestStorage.file(ctx)
    private fun field(name: String) = MonitorService::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before fun isolate() {
        preferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        oldState = MonitorService.state
        original = History.snapshot(ctx)
        History.replaceSnapshot(ctx, emptyList())
        Onboarding.markSeen(ctx)
    }

    @After fun restore() {
        field("running").setBoolean(null, false)
        if (historyFile.isDirectory) assertTrue(historyFile.delete())
        History.replaceSnapshot(ctx, original)
        field("state").set(null, oldState)
        val edit = Prefs.sp(ctx).edit().clear()
        preferences.forEach { (key, value) -> put(edit, key, value) }
        assertTrue(edit.commit())
    }

    @Test fun selectedWindowUptimeAndCoverageShareRevision() {
        val now = System.currentTimeMillis()
        val rows = listOf(Observation(now - 3 * 86_400_000L, now - 3 * 86_400_000L + 600_000, "OK", 100),
            Observation(now - 120_000, now - 60_000, "TG_DOWN"))
        History.replaceSnapshot(ctx, rows)
        val expected = Timeline.stats(rows, now - 7 * 86_400_000L, now)
        val week = HistoryDisplay.load(ctx, 7)
        assertEquals("The week's uptime must not use the day's denominator", expected.uptimePercent, week.uptime, 0.001)
        assertEquals(History.version, week.version)
        assertEquals(7 * CoverageStats.DAY_MS, week.coverage.windowMs)
        assertEquals(660_000L, week.coverage.assessedMs)
    }

    @Test fun healthSnapshotNeverWaitsForHistoryDiskMonitor() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = Thread {
            synchronized(History) { held.countDown(); release.await(10, TimeUnit.SECONDS) }
        }.apply { start() }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        try {
            val started = SystemClock.elapsedRealtime()
            val snapshot = MonitorService.healthSnapshot()
            assertTrue(SystemClock.elapsedRealtime() - started < 250)
            assertTrue(snapshot.primaryBusy in 0..16)
            assertTrue(snapshot.secondaryBusy in 0..3)
        } finally { release.countDown(); blocker.join(5_000) }
    }

    @Test fun storageFailureIsNotOutage() {
        val state = MonitorService.State(MonitorService.Status.OK, System.currentTimeMillis(),
            latencyMs = 50, checkedElapsed = SystemClock.elapsedRealtime())
        field("state").set(null, state)
        field("running").setBoolean(null, true)
        assertTrue(historyFile.delete())
        assertTrue(historyFile.mkdir())
        History::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val healthId = ctx.resources.getIdentifier("tvMonitorHealth", "id", ctx.packageName)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var displayed = false
            while (!displayed && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    displayed = activity.findViewById<TextView>(healthId)?.text?.contains(
                        (if (ctx.getString(R.string.lang) == "ru") "История недоступна" else "History unavailable")) == true
                }
                if (!displayed) SystemClock.sleep(100)
            }
            assertTrue("History failure belongs in monitor health", displayed)
            assertSame("A storage failure must not replace the measured network observation", state, MonitorService.state)
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.history_load_failed),
                    activity.findViewById<TextView>(R.id.tvDayStats).text.toString())
            }
        }
    }

    private fun put(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
