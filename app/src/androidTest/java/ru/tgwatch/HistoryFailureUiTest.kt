package ru.tgwatch

import android.content.SharedPreferences
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryFailureUiTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var original: List<Observation>
    private lateinit var preferences: Map<String, *>
    private lateinit var file: File

    @Before fun makeHistoryUnreadable() {
        preferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        original = History.snapshot(ctx)
        History.replaceSnapshot(ctx, emptyList())
        Prefs.sp(ctx).edit().putBoolean("onboarding_seen", true).commit()
        file = File(ctx.createDeviceProtectedStorageContext().filesDir, "history-v2.csv")
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        History::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
    }

    @After fun restore() {
        assertTrue(file.delete())
        History.replaceSnapshot(ctx, original)
        val edit = Prefs.sp(ctx).edit().clear()
        preferences.forEach { (key, value) -> put(edit, key, value) }
        assertTrue(edit.commit())
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

    @Test fun unreadableHistoryIsNotNoOutages() {
        ActivityScenario.launch(IncidentActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            var failed = false
            while (!failed && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity {
                    failed = it.findViewById<TextView>(R.id.incidentEmpty).text.toString() ==
                        it.getString(R.string.history_load_failed)
                }
                if (!failed) SystemClock.sleep(100)
            }
            assertTrue("An unavailable history must not say there were no outages", failed)
        }
    }

    @Test fun unreadableHistoryIsNotFreshStats() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            var failed = false
            while (!failed && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity {
                    failed = it.findViewById<TextView>(R.id.tvDayStats).text.toString() ==
                        it.getString(R.string.history_load_failed)
                }
                if (!failed) SystemClock.sleep(100)
            }
            assertTrue("An unavailable history must not display valid zero-check statistics", failed)
        }
    }
}
