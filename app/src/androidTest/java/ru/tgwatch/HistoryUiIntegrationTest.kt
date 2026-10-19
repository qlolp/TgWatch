package ru.tgwatch

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryUiIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private var original: List<Observation> = emptyList()
    private var settings: Map<String, *> = emptyMap<String, Any>()

    @Before fun isolateScreenFixture() {
        settings = Prefs.sp(ctx).all
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        assertFalse(MonitorService.running)
        original = History.snapshot(ctx)
        History.replaceSnapshot(ctx, emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${ctx.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    @After fun restoreScreenFixture() {
        MonitorService.stopAndAwait(ctx)
        History.replaceSnapshot(ctx, original)
        val edit = Prefs.sp(ctx).edit().clear()
        settings.forEach { (key, value) -> put(edit, key, value) }
        assertTrue(edit.commit())
        MonitorService.clearRestoredState()
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

    private fun awaitSummary(scenario: ActivityScenario<MainActivity>, checks: Int) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var matched = false
        while (!matched && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                val text = activity.findViewById<TextView>(R.id.tvDayStats).text.toString()
                matched = text.contains((if (ctx.getString(R.string.lang) == "ru") "Проверок: $checks." else "Checks: $checks.")) &&
                    activity.findViewById<Button>(R.id.btnShareStats).isEnabled
            }
            if (!matched) SystemClock.sleep(100)
        }
        assertTrue("Expected a fresh summary with $checks checks", matched)
    }

    @Test fun periodSwitchAndRestoreDisplayOnlyCurrentWindow() {
        val now = System.currentTimeMillis()
        History.replaceSnapshot(ctx, listOf(Observation(now - 2 * 86_400_000, now - 2 * 86_400_000 + 10_000, "TG_DOWN"),
            Observation(now - 10_000, now - 1000, "OK", 10)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitSummary(scenario, 1)
            scenario.onActivity { it.findViewById<Button>(R.id.btnPeriod).performClick() }
            awaitSummary(scenario, 2)
            History.replaceSnapshot(ctx, listOf(Observation(now - 2000, now - 1000, "PARTIAL")))
            awaitSummary(scenario, 1)
            scenario.recreate()
            awaitSummary(scenario, 1)
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.period_week), activity.findViewById<Button>(R.id.btnPeriod).text)
                assertEquals(activity.getString(R.string.chart_last_week), activity.findViewById<TextView>(R.id.tvChartTitle).text)
            }
        }
    }

    @Test fun resumeAlertsButtonClearsOnlyTheMute() {
        Prefs.setQuietHoursEnabled(ctx, true)
        Prefs.setSoundFor(ctx, "PARTIAL", false)
        assertTrue(Prefs.muteAlerts(ctx))
        val preserved = Prefs.sp(ctx).all.filterKeys { !it.startsWith("alert_mute_") && it != "alerts_muted_until" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<Button>(R.id.btnResumeAlerts)
                assertEquals(View.VISIBLE, button.visibility)
                button.performClick()
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while ((Prefs.alertsMutedUntil(ctx) != 0L || TargetSettingsExecutor.busy(TargetId.TELEGRAM)) &&
                SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertEquals(0L, Prefs.alertsMutedUntil(ctx))
            var hidden = false
            while (!hidden && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { hidden = it.findViewById<Button>(R.id.btnResumeAlerts).visibility == View.GONE }
                if (!hidden) SystemClock.sleep(25)
            }
            assertTrue("The completed settings write must hide Resume alerts", hidden)
            assertTrue(Prefs.quietHoursEnabled(ctx))
            assertFalse(Prefs.soundFor(ctx, "PARTIAL"))
            preserved.forEach { (key, value) -> assertEquals("Unrelated setting $key", value, Prefs.sp(ctx).all[key]) }
        }
    }

    @Test fun busyHistoryStorageDoesNotBlockMainScreen() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding = AtomicBoolean(false)
        val holder = Thread {
            synchronized(History) {
                holding.set(true)
                held.countDown()
                try { release.await(30, TimeUnit.SECONDS) } finally { holding.set(false) }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitSummary(scenario, 0)
            holder.start()
            assertTrue(held.await(2, TimeUnit.SECONDS))
            try {
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.btnPeriod).performClick()
                    assertTrue("A period switch must return while history is still busy", holding.get())
                    assertEquals(activity.getString(R.string.check_now), activity.findViewById<Button>(R.id.btnCheck).text)
                }
                release.countDown()
                awaitSummary(scenario, 0)
            } finally {
                release.countDown()
                holder.join(2000)
            }
        }
    }

    @Test fun incidentCardsFollowPeriodAndShareOnlyExplicitRedactedSummary() {
        val now = System.currentTimeMillis()
        val old = now - 2 * 86_400_000
        History.replaceSnapshot(ctx, listOf(Observation(old, old + 1000, "TG_DOWN"),
            Observation(old + 1000, old + 2000, "OK", 10),
            Observation(now - 6000, now - 5000, "PARTIAL"),
            Observation(now - 5000, now - 4000, "NO_NETWORK"),
            Observation(now - 3000, now - 2000, "OK", 10)))
        val secret = "PRIVATE_ENDPOINT_TOKEN 192.168.9.7"
        assertTrue(Prefs.sp(ctx).edit().putString("incident_private_fixture", secret).commit())
        val captured = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                captured.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val intent = Intent(ctx, IncidentActivity::class.java).putExtra("history_days", 1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<IncidentActivity>(intent).use { scenario ->
                fun awaitCards(count: Int) {
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    var ready = false
                    while (!ready && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { activity ->
                            ready = activity.findViewById<ListView>(R.id.incidentList).adapter.count == count
                        }
                        if (!ready) SystemClock.sleep(100)
                    }
                    assertTrue("Expected $count incident cards", ready)
                }
                awaitCards(1)
                assertNull("Loading cards must never open sharing", captured.get())
                scenario.onActivity { activity ->
                    val list = activity.findViewById<ListView>(R.id.incidentList)
                    val row = list.adapter.getView(0, null, list)
                    val body = row.findViewById<TextView>(R.id.incidentText).text.toString()
                    assertTrue(body, body.contains((if (ctx.getString(R.string.lang) == "ru") "Нет сети:" else "No internet:")))
                    assertTrue(body, body.contains((if (ctx.getString(R.string.lang) == "ru") "Нет данных:" else "No data:")))
                    row.findViewById<Button>(R.id.incidentShare).performClick()
                }
                @Suppress("DEPRECATION")
                val send = captured.get().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("text/plain", send.type)
                val shared = send.getStringExtra(Intent.EXTRA_TEXT)!!
                for (value in listOf(secret, "192.168.", "endpoint", "https://", "token")) assertFalse(value, shared.contains(value))
                scenario.onActivity { it.findViewById<Button>(R.id.incidentPeriod).performClick() }
                awaitCards(2)
                scenario.recreate()
                awaitCards(2)
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
