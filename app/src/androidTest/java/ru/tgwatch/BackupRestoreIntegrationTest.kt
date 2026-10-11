package ru.tgwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import android.os.SystemClock
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

/** Exercises real encrypted backups, Android persistence and service lifecycle, without mocks. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private var previousSettings: Map<String, *>? = null
    private var previousHistory: List<Observation>? = null
    private var previousLog: List<String>? = null

    private val portableSettings: Map<String, Any> = linkedMapOf(
        "power_profile" to "CUSTOM", "interval_sec" to 47, "keep_awake" to false,
        "vibrate" to false, "vibrate_partial" to true, "vibrate_offline" to true,
        "vibrate_recovery" to false, "notify_recovery" to false, "event_sound" to true,
        "vibration_pattern" to "SHORT", "quiet_hours" to true,
        "quiet_start_hour" to 23, "quiet_end_hour" to 8,
        "quiet_start_minute" to 1395, "quiet_end_minute" to 525,
        "notify_outage" to false, "notify_partial" to true,
        "sound_outage" to false, "sound_partial" to true, "sound_recovery" to true,
    )

    @Before fun saveCurrentDataAndStopMonitoring() {
        assertNotSame("Backup APIs must run on the instrumentation worker", Looper.getMainLooper(), Looper.myLooper())
        assertFalse("A previous transaction must not be silently discarded", BackupManager.hasPending(ctx))
        previousSettings = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        previousHistory = History.snapshot(ctx)
        previousLog = EventLog.all(ctx).toList()
        Prefs.sp(ctx).edit().clear().putBoolean("enabled", false).commit()
        History.replaceSnapshot(ctx, emptyList())
        EventLog.replace(ctx, emptyList())
        LastSuccessStore.clear(ctx)
        MonitorService.clearRestoredState()
    }

    @After fun restoreTestFixture() {
        if (previousSettings == null) return
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (BackupManager.hasPending(ctx)) BackupManager.recoverPending(ctx)
        previousHistory?.let { History.replaceSnapshot(ctx, it) }
        writeSettings(previousSettings!!, clear = true)
        // The preferences also contain the durable log: restore the adapter last so its cache
        // and encoded value remain identical even when stopping added a fixture teardown event.
        previousLog?.let { EventLog.replace(ctx, it) }
        MonitorService.clearRestoredState()
    }

    private fun writeSettings(values: Map<String, *>, clear: Boolean = false) {
        val edit = Prefs.sp(ctx).edit()
        if (clear) edit.clear()
        values.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            null -> edit.remove(key)
            else -> error("Unsupported fixture preference")
        } }
        assertTrue("Fixture preferences must persist", edit.commit())
    }

    private fun snapshot(): BackupSnapshot {
        val now = System.currentTimeMillis()
        return BackupSnapshot(now, portableSettings,
            listOf(Observation(now - 90_000, now - 60_000, "OK", 42, 9),
                // Future coverage exposes a late History.endSession clipping restored rows.
                Observation(now - 30_000, now + 30_000, "TG_DOWN", -1, 9)),
            listOf("10.10 12:05:00  Связь восстановлена", "10.10 12:04:00  Частично доступен"))
    }

    private fun seed(snapshot: BackupSnapshot) {
        writeSettings(snapshot.settings)
        History.replaceSnapshot(ctx, snapshot.observations)
        EventLog.replace(ctx, snapshot.log)
    }

    private fun seedTransientStatus() {
        val now = System.currentTimeMillis()
        Prefs.saveLastState(ctx, MonitorService.Status.OK, now, now - 1000, 7,
            "old device status", now - 500, "old Wi-Fi diagnostics", 47)
        LastSuccessStore.record(ctx, "OK", now)
        MonitorService.restorePersistedState(ctx)
        assertEquals(MonitorService.Status.OK, MonitorService.state.status)
    }

    private fun assertPortableData(expected: BackupSnapshot) {
        assertEquals(expected.settings, Prefs.sp(ctx).all.filterKeys { it in portableSettings })
        assertEquals(expected.observations, History.snapshot(ctx))
        assertEquals(expected.log, EventLog.all(ctx))
        // Verify the durable Android files too, not merely the in-memory adapters.
        val dp = ctx.createDeviceProtectedStorageContext()
        assertEquals(expected.observations, HistoryStorage.read(File(dp.filesDir, "history-v2.csv")))
        assertEquals(expected.log, EventLogStorage.decode(
            dp.getSharedPreferences("tgwatch", android.content.Context.MODE_PRIVATE).getString("log", "")!!))
    }

    private fun assertMonitoringDisabledAndDiagnosticsCleared() {
        assertFalse(Prefs.isEnabled(ctx))
        assertFalse(MonitorService.running)
        assertNull(Prefs.loadLastState(ctx))
        assertEquals(0L, LastSuccessStore.lastSuccessAt(ctx))
        assertEquals(MonitorService.Status.UNKNOWN, MonitorService.state.status)
        assertEquals(0L, MonitorService.state.checkedAt)
        assertEquals(0L, MonitorService.state.lastVibrationAt)
        assertEquals("", MonitorService.state.diagnostics)
        for (key in listOf("last_status", "last_checked", "last_since", "last_latency", "last_reason",
            "last_vibe", "last_diagnostics", "last_expected_sec", "last_success_at")) {
            assertFalse("Transient preference $key leaked from restore", Prefs.sp(ctx).contains(key))
        }
        assertFalse(BackupManager.hasPending(ctx))
    }

    private fun rejected(action: () -> Unit) {
        try {
            action()
            fail("Invalid backup must be rejected")
        } catch (_: IllegalArgumentException) {
            // Authentication/schema rejection is the public boundary; no message/secret is needed.
        }
    }

    @Test fun encryptedRoundTripReplacesPortableDataAndClearsDeviceStatus() {
        val source = snapshot()
        seed(source)
        seedTransientStatus()
        val password = "integration-password".toCharArray()
        try {
            val encrypted = BackupManager.export(ctx, password)
            val decoded = BackupManager.decrypt(encrypted, password)
            assertEquals(source.settings, decoded.settings)
            assertEquals(source.observations, decoded.observations)
            assertEquals(source.log, decoded.log)
            assertTrue(decoded.createdAt >= source.createdAt)
            assertFalse(decoded.settings.keys.any { it == "enabled" || it.startsWith("last_") })
            seed(source.copy(settings = mapOf("interval_sec" to 10), observations = emptyList(),
                log = listOf("destination-only event")))
            Prefs.sp(ctx).edit().putBoolean("enabled", true).commit()
            BackupManager.restore(ctx, decoded)
            assertPortableData(decoded)
            assertMonitoringDisabledAndDiagnosticsCleared()
            assertFalse("Completed restore must not replay", BackupManager.recoverPending(ctx))
        } finally {
            password.fill('\u0000')
        }
    }

    @Test fun wrongPasswordTamperingAndTruncationDoNotMutateCurrentData() {
        val current = snapshot()
        seed(current)
        seedTransientStatus()
        val password = "integration-password".toCharArray()
        val wrong = "different-password".toCharArray()
        try {
            val encrypted = BackupManager.export(ctx, password)
            val beforeSettings = Prefs.sp(ctx).all.toMap()
            val beforeState = MonitorService.state
            val corrupt = encrypted.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            val attempts: List<() -> Unit> = listOf(
                { BackupManager.decrypt(encrypted, wrong); Unit },
                { BackupManager.decrypt(corrupt, password); Unit },
                { BackupManager.decrypt(encrypted.copyOf(12), password); Unit },
            )
            attempts.forEach { attempt ->
                rejected(attempt)
                assertEquals(beforeSettings, Prefs.sp(ctx).all)
                assertEquals(beforeState, MonitorService.state)
                assertFalse(MonitorService.running)
                assertPortableData(current)
                assertFalse(BackupManager.hasPending(ctx))
            }
        } finally {
            password.fill('\u0000')
            wrong.fill('\u0000')
        }
    }

    @Test fun directRestoreValidatesWholeSnapshotBeforeAnyMutation() {
        val current = snapshot()
        seed(current)
        seedTransientStatus()
        Prefs.sp(ctx).edit().putBoolean("enabled", true).commit()
        val beforeSettings = Prefs.sp(ctx).all.toMap()
        val beforeState = MonitorService.state
        val invalid = listOf(
            current.copy(settings = current.settings + ("quiet_start_minute" to 1440)),
            current.copy(settings = current.settings + ("enabled" to true)),
            current.copy(settings = current.settings + ("interval_sec" to 47L)),
            current.copy(observations = listOf(current.observations.first().copy(until = 0))),
            current.copy(log = listOf("x".repeat(BackupCodec.MAX_LOG_LINE_CHARS + 1))),
        )
        invalid.forEach { candidate ->
            rejected { BackupManager.restore(ctx, candidate) }
            assertEquals(beforeSettings, Prefs.sp(ctx).all)
            assertEquals(beforeState, MonitorService.state)
            assertTrue("Validation must occur before disabling monitoring", Prefs.isEnabled(ctx))
            assertFalse(BackupManager.hasPending(ctx))
            assertPortableData(current)
        }
    }

    @Test fun realServiceTeardownFinishesBeforeReplacementAndCannotWriteLater() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        val restored = snapshot()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Prefs.setEnabled(activity, true)
                MonitorService.start(activity)
            }
            val startupDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < startupDeadline &&
                (!MonitorService.running || EventLog.all(ctx).none { it.contains("Мониторинг запущен") })) {
                SystemClock.sleep(25)
            }
            assertTrue("Test must exercise an actual running service", MonitorService.running)
            assertTrue(EventLog.all(ctx).any { it.contains("Мониторинг запущен") })
            BackupManager.restore(ctx, restored)
            instrumentation.waitForIdleSync()
            assertPortableData(restored)
            assertMonitoringDisabledAndDiagnosticsCleared()
            // Wait through the existing 12-second probe deadline; a cancelled old check or a late
            // onDestroy must never append records, clip restored coverage, or reintroduce status.
            val quietUntil = SystemClock.elapsedRealtime() + 13_000
            while (SystemClock.elapsedRealtime() < quietUntil) {
                assertPortableData(restored)
                assertMonitoringDisabledAndDiagnosticsCleared()
                SystemClock.sleep(100)
            }
        }
        instrumentation.waitForIdleSync()
        assertPortableData(restored)
        assertMonitoringDisabledAndDiagnosticsCleared()
    }

    @Test fun pendingTransactionReplaysPartialApplicationAndThenIsIdempotent() {
        val restored = snapshot()
        val pending = File(ctx.createDeviceProtectedStorageContext().filesDir, "restore-pending-v1.json")
        // Simulate a process dying after durable staging and only part of applying the snapshot.
        RestoreTransaction(pending).stage(BackupCodec.encode(restored))
        assertTrue(BackupManager.hasPending(ctx))
        History.replaceSnapshot(ctx, restored.observations.take(1))
        EventLog.replace(ctx, listOf("partially applied old journal"))
        writeSettings(mapOf("interval_sec" to 10, "enabled" to true))
        seedTransientStatus()
        assertTrue(BackupManager.recoverPending(ctx))
        assertPortableData(restored)
        assertMonitoringDisabledAndDiagnosticsCleared()
        assertFalse(BackupManager.recoverPending(ctx))
        assertPortableData(restored)
    }

    @Test fun pendingJournalBlocksEnableAndQueuedDisabledStartDoesNotClipRestoredCoverage() {
        val restored = snapshot()
        BackupManager.restore(ctx, restored)
        val pending = File(ctx.createDeviceProtectedStorageContext().filesDir, "restore-pending-v1.json")
        RestoreTransaction(pending).stage(BackupCodec.encode(restored))
        Prefs.setEnabled(ctx, true)
        assertFalse("Pending restore must prevent re-enabling checks", Prefs.isEnabled(ctx))
        assertTrue(BackupManager.hasPending(ctx))

        val destroyed = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == MonitorService.ACTION_STATE_CHANGED && !MonitorService.running &&
                    MonitorService.state.checkedAt == 0L) destroyed.countDown()
            }
        }
        val filter = IntentFilter(MonitorService.ACTION_STATE_CHANGED)
        ctx.registerReceiver(receiver, filter,
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // Bypass guarded start() to simulate an already queued framework start intent.
                scenario.onActivity { activity ->
                    assertFalse(Prefs.isEnabled(activity))
                    activity.startForegroundService(Intent(activity, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_START))
                }
                assertTrue("A real disabled service must reach onDestroy", destroyed.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertFalse(MonitorService.running)
                assertFalse(Prefs.isEnabled(ctx))
                assertTrue(BackupManager.hasPending(ctx))
                // The last row ends in the future. An unconditional endSession would clip it.
                assertPortableData(restored)
            }
        } finally {
            ctx.unregisterReceiver(receiver)
        }
        assertTrue(BackupManager.recoverPending(ctx))
        assertPortableData(restored)
        assertMonitoringDisabledAndDiagnosticsCleared()
    }

    @Test fun historyImportBlocksRestartBeforeJournalIsStaged() {
        val current = snapshot()
        seed(current)
        val mainEntered = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        android.os.Handler(Looper.getMainLooper()).post {
            mainEntered.countDown()
            releaseMain.await(10, TimeUnit.SECONDS)
        }
        try {
            assertTrue(mainEntered.await(5, TimeUnit.SECONDS))
            Thread {
                try { BackupManager.importHistory(ctx, current.observations) }
                catch (error: Throwable) { failure.set(error) }
                finally { completed.countDown() }
            }.start()
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!BackupManager.hasPending(ctx) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
            assertTrue("CSV must guard restarts even before durable staging", BackupManager.hasPending(ctx))
            assertFalse(File(ctx.createDeviceProtectedStorageContext().filesDir, "restore-pending-v1.json").exists())
            Prefs.setEnabled(ctx, true)
            MonitorService.start(ctx)
            assertFalse("A tile or activity cannot restart during CSV teardown", Prefs.isEnabled(ctx))
        } finally { releaseMain.countDown() }
        assertTrue(completed.await(15, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("CSV import failed", it) }
        assertFalse(BackupManager.hasPending(ctx))
        assertPortableData(current)
        assertMonitoringDisabledAndDiagnosticsCleared()
    }

    @Test fun historyImportPreservesCurrentSettingsAndJournalIncludingRealStopEvent() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        val current = snapshot()
        seed(current)
        val now = System.currentTimeMillis()
        val imported = listOf(Observation(now - 20_000, now + 40_000, "PARTIAL", 81, 4))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Prefs.setEnabled(activity, true)
                MonitorService.start(activity)
            }
            val startupDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < startupDeadline &&
                (!MonitorService.running || EventLog.all(ctx).none { it.contains("Мониторинг запущен") })) {
                SystemClock.sleep(25)
            }
            assertTrue("Import must exercise an active monitor", MonitorService.running)
            assertTrue(EventLog.all(ctx).any { it.contains("Мониторинг запущен") })
            val beforeSettings = Prefs.sp(ctx).all.filterKeys { it in portableSettings }
            val beforeLog = EventLog.all(ctx).toList()
            BackupManager.importHistory(ctx, imported)
            instrumentation.waitForIdleSync()
            val afterLog = EventLog.all(ctx)
            assertEquals("History import must preserve the user's existing journal",
                beforeLog, afterLog.takeLast(beforeLog.size))
            // A real check can finish between the capture and stop request. Keep its legitimate
            // transition too; require exactly one shutdown event without assuming an idle probe.
            val additions = afterLog.dropLast(beforeLog.size)
            assertEquals(1, additions.count { it.endsWith("Мониторинг остановлен") })
            assertTrue(afterLog.first().endsWith("Мониторинг остановлен"))
            assertEquals(beforeSettings, Prefs.sp(ctx).all.filterKeys { it in portableSettings })
            assertPortableData(current.copy(observations = imported, log = afterLog))
            assertMonitoringDisabledAndDiagnosticsCleared()
        }
    }
}
