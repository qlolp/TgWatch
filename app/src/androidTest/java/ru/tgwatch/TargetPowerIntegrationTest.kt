package ru.tgwatch

import android.content.Context
import android.content.Intent
import android.app.AlertDialog
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetPowerIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preferences: Map<String, *>
    private lateinit var backup: TargetBackupSnapshot

    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        preferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        backup = BackupManager.snapshotTargets(ctx)
        Onboarding.markSeen(ctx)
        check(Prefs.sp(ctx).edit().remove("secondary_power_saving").commit())
    }
    @After fun restore() {
        if (::backup.isInitialized) BackupManager.restoreTargets(ctx, backup)
        if (::preferences.isInitialized) {
            val edit = Prefs.sp(ctx).edit().clear()
            preferences.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            check(edit.commit())
        }
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }

    @Test fun optionDefaultsOffAndDoesNotEnableGoalsOrEnterEitherBackup() {
        assertFalse(TargetPowerSettings.enabled(ctx))
        val before = TargetPreferences.definitions(ctx)
        assertTrue(TargetPowerSettings.setEnabled(ctx, true))
        assertTrue(TargetPowerSettings.enabled(ctx))
        assertEquals(before, TargetPreferences.definitions(ctx))
        assertFalse(BackupManager.snapshot(ctx).settings.containsKey("secondary_power_saving"))
        assertFalse(BackupManager.snapshotTargets(ctx).globalSettings.containsKey("secondary_power_saving"))
    }
    @Test fun runtimeIntervalSurvivesDiskWithoutPersistingElapsedClock() {
        val state = TargetSnapshot(TargetId.MATRIX, TargetStatus.OK, System.currentTimeMillis(),
            SystemClock.elapsedRealtime(), expectedIntervalSec = 600,
            endpointRevision = TargetPreferences.endpointRevision(ctx, TargetId.MATRIX))
        TargetStateStore.persist(ctx, state)
        val loaded = TargetStateStore.load(ctx, TargetId.MATRIX)
        assertEquals(600, loaded.expectedIntervalSec)
        assertEquals(0L, loaded.checkedElapsedMs)
        assertEquals(state.checkedAtMs, loaded.checkedAtMs)
    }
    @Test fun targetDialogExplainsAndSavesDeviceOptionWithoutEnablingMonitoring() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = TargetSettingsDialog.show(activity, TargetId.MATRIX)
                val option = dialog.findViewById<CheckBox>(R.id.targetPowerSaving)
                assertFalse(option.isChecked)
                option.isChecked = true
                assertTrue(dialog.findViewById<TextView>(R.id.targetPowerInterval).text.isNotBlank())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!TargetPowerSettings.enabled(ctx) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertTrue(TargetPowerSettings.enabled(ctx))
            assertFalse(Prefs.isEnabled(ctx))
        }
    }
    @Test fun invalidBatteryScaleOrLevelIsUnknown() {
        fun battery(level: Int, scale: Int) = Intent().putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, scale)
        assertNull(TargetPowerMonitor.batteryPercent(null))
        assertNull(TargetPowerMonitor.batteryPercent(battery(1, 0)))
        assertNull(TargetPowerMonitor.batteryPercent(battery(-1, 100)))
        assertNull(TargetPowerMonitor.batteryPercent(battery(51, 50)))
        assertEquals(50, TargetPowerMonitor.batteryPercent(battery(25, 50)))
    }
    @Test fun inputRefreshDeduplicatesChangesAndStopsAfterClose() {
        val input = AtomicReference(TargetPowerState(true, false, 16, false))
        val seen = CopyOnWriteArrayList<TargetPowerState>()
        val monitor = TargetPowerMonitor(ctx, { seen.add(it) }, { input.get() })
        try {
            input.set(input.get().copy(batteryPercent = 15)); monitor.refresh()
            assertEquals(600, TargetPowerPolicy.intervalSec(60, true, seen.last()))
            input.set(input.get().copy(charging = true)); monitor.refresh()
            assertEquals(60, TargetPowerPolicy.intervalSec(60, true, seen.last()))
            input.set(input.get().copy(powerSave = true)); monitor.refresh()
            assertEquals(600, TargetPowerPolicy.intervalSec(60, true, seen.last()))
            monitor.refresh()
            assertEquals(3, seen.size)
        } finally { monitor.close() }
        monitor.close()
        input.set(input.get().copy(powerSave = false)); monitor.refresh()
        assertEquals(3, seen.size)
    }
    @Test fun bothBackupReadersResetDeviceOptionAndKeepTargetsStopped() {
        val legacy = BackupManager.snapshot(ctx)
        assertTrue(TargetPowerSettings.setEnabled(ctx, true))
        BackupManager.restoreTargets(ctx, backup)
        assertFalse(TargetPowerSettings.enabled(ctx))
        assertTrue(TargetPreferences.definitions(ctx).none { it.enabled })
        assertTrue(TargetPowerSettings.setEnabled(ctx, true))
        BackupManager.restore(ctx, legacy)
        assertFalse(TargetPowerSettings.enabled(ctx))
        assertTrue(TargetPreferences.definitions(ctx).none { it.enabled })
    }
    @Test fun actualSecondarySchedulerPublishesAndWaitsUsingEffectiveInterval() {
        check(Prefs.sp(ctx).edit().putBoolean("enabled", true)
            .putBoolean(TargetPreferences.key(TargetId.TELEGRAM, "enabled"), false)
            .putBoolean(TargetPreferences.key(TargetId.MATRIX, "enabled"), true)
            .putBoolean(TargetPreferences.key(TargetId.WHATSAPP, "enabled"), false)
            .putBoolean(TargetPreferences.key(TargetId.CUSTOM, "enabled"), false).commit())
        assertTrue(TargetPowerSettings.setEnabled(ctx, true))
        val thread = HandlerThread("target-power-test").apply { start() }
        val handler = Handler(thread.looper)
        val primary = Executors.newSingleThreadExecutor()
        val secondary = Executors.newSingleThreadExecutor()
        val published = AtomicReference<TargetSnapshot>()
        val done = CountDownLatch(1)
        val reference = AtomicReference<TargetMonitoring>()
        val host = object : TargetMonitorHost {
            override val context: Context = ctx
            override val worker = handler
            override val primaryPool = primary
            override val secondaryPool = secondary
            override fun isCurrent() = true
            override fun acquireWakeLock() = Unit
            override fun onProgress() = Unit
            override fun onPublished(snapshot: TargetSnapshot, report: TargetReport) {
                published.set(snapshot); done.countDown()
            }
        }
        try {
            handler.post {
                val monitor = TargetMonitoring(host, powerStateReader = { TargetPowerState(false, true, 15, false) },
                    runnerFactory = { TargetRunner({ error("Not Telegram") }, { endpoints, _ ->
                    endpoints.map { ProbeOutcome(it.name, it.group, true, 1, "ok") }
                }) })
                reference.set(monitor); monitor.startDue()
            }
            assertTrue("Secondary measurement was not published", done.await(10, TimeUnit.SECONDS))
            val drained = CountDownLatch(1)
            handler.post { drained.countDown() }
            assertTrue(drained.await(3, TimeUnit.SECONDS))
            val state = checkNotNull(published.get())
            assertEquals(600, state.expectedIntervalSec)
            assertEquals(600, reference.get().expectedIntervals.getValue(TargetId.MATRIX))
            assertTrue(reference.get().nextWakeElapsed()!! >= state.checkedElapsedMs + 600_000)
        } finally {
            val closed = CountDownLatch(1)
            handler.post { reference.get()?.close(); closed.countDown() }
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            primary.shutdownNow(); secondary.shutdownNow()
            thread.quitSafely(); thread.join(3_000)
        }
    }
}
