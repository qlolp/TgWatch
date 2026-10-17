package ru.tgwatch

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TargetNotificationIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = ctx.getSystemService(NotificationManager::class.java)
    private lateinit var saved: Map<String, *>
    private lateinit var log: List<String>
    private lateinit var history: TargetBackupSnapshot
    private lateinit var controller: TargetAlertController
    private val matrix = TargetId.MATRIX
    private val whatsapp = TargetId.WHATSAPP

    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        saved = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        log = EventLog.all(ctx).toList()
        history = BackupManager.snapshotTargets(ctx)
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val edit = Prefs.sp(ctx).edit().putBoolean("enabled", true).putBoolean("quiet_hours", false)
        saved.keys.filter { it.startsWith("alert_mute_") || it == "alerts_muted_until" || it.contains(".mute_") }
            .forEach(edit::remove)
        for (id in TargetId.entries) edit.putBoolean(TargetPreferences.key(id, "enabled"), true)
            .putBoolean(TargetPreferences.key(id, "notify_outage"), true)
            .putBoolean(TargetPreferences.key(id, "notify_recovery"), true)
            .putBoolean(TargetPreferences.key(id, "notify_slow"), false)
        check(edit.commit())
        EventNotifications(ctx, manager)
        clearAll()
        controller = TargetAlertController(ctx, manager) { true }
    }
    @After fun restore() {
        if (::controller.isInitialized) controller.close()
        clearAll()
        if (::history.isInitialized) BackupManager.restoreTargets(ctx, history)
        if (::log.isInitialized) EventLog.replace(ctx, log)
        if (::saved.isInitialized) {
            val edit = Prefs.sp(ctx).edit().clear()
            saved.forEach { (key, value) -> when (value) {
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

    @Test fun goalsHaveIndependentOutagesAndInitialOkIsSilent() {
        controller.onCheck(state(matrix, TargetStatus.OK))
        assertNull(notification(matrix, "recovery"))
        controller.onCheck(state(matrix, TargetStatus.DOWN))
        controller.onCheck(state(whatsapp, TargetStatus.DOWN))
        await { notification(matrix, "outage") != null && notification(whatsapp, "outage") != null }
        val first = notification(matrix, "outage")!!
        val second = notification(whatsapp, "outage")!!
        assertTrue(first.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Matrix"))
        assertTrue(second.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("WhatsApp"))
        assertNotEquals(first.contentIntent, second.contentIntent)
        assertNotEquals(first.actions[0].actionIntent, second.actions[0].actionIntent)
        assertNotEquals(first.actions[1].actionIntent, second.actions[1].actionIntent)
        controller.forget(matrix)
        await { notification(matrix, "outage") == null }
        assertNotNull(notification(whatsapp, "outage"))
    }

    @Test fun matrixMuteActionClearsOnlyItsOwnAlerts() {
        EventNotifications(ctx, manager).outage("Legacy Telegram", false, true)
        controller.onCheck(state(matrix, TargetStatus.DOWN))
        controller.onCheck(state(whatsapp, TargetStatus.DOWN))
        await { notification(matrix, "outage") != null && notification(whatsapp, "outage") != null }
        notification(matrix, "outage")!!.actions[1].actionIntent.send()
        await { notification(matrix, "outage") == null && !TargetSettingsExecutor.busy(matrix) }
        assertNotNull(notification(whatsapp, "outage"))
        assertTrue(manager.activeNotifications.any { it.id == EventNotifications.OUTAGE_ID })
        assertFalse(Prefs.alertsAllowed(ctx, System.currentTimeMillis(), matrix))
        assertTrue(Prefs.alertsAllowed(ctx, System.currentTimeMillis(), whatsapp))
    }

    @Test fun staleAndUnknownReportsNeverCreateAnOutage() {
        controller.onCheck(state(matrix, TargetStatus.UNKNOWN))
        val old = state(matrix, TargetStatus.DOWN).copy(
            checkedElapsedMs = (SystemClock.elapsedRealtime() - 600_000).coerceAtLeast(0),
            checkedAtMs = System.currentTimeMillis() - 600_000)
        controller.onCheck(old)
        assertNull(notification(matrix, "outage"))
        controller.onCheck(state(matrix, TargetStatus.DOWN))
        await { notification(matrix, "outage") != null }
    }

    @Test fun lateRecoveryCannotReplaceANewerOutageOrAnotherGoalsAlert() {
        controller.onCheck(state(matrix, TargetStatus.DOWN))
        val ok = state(matrix, TargetStatus.OK)
        controller.onCheck(ok)
        controller.recovered(ok, RecoveryEvent(1_000))
        await { notification(matrix, "recovery") != null }
        controller.onCheck(state(matrix, TargetStatus.DOWN))
        controller.onCheck(state(whatsapp, TargetStatus.DOWN))
        controller.recovered(ok, RecoveryEvent(1_000))
        await { notification(matrix, "outage") != null }
        assertNull(notification(matrix, "recovery"))
        assertNotNull(notification(whatsapp, "outage"))
    }

    @Test fun slowThresholdsStreaksAndMutesAreIndependent() {
        check(Prefs.sp(ctx).edit().putBoolean(TargetPreferences.key(matrix, "notify_slow"), true)
            .putBoolean(TargetPreferences.key(whatsapp, "notify_slow"), true)
            .putLong(TargetPreferences.key(matrix, "slow_threshold"), 500)
            .putLong(TargetPreferences.key(whatsapp, "slow_threshold"), 1_000).commit())
        repeat(3) {
            controller.onCheck(state(matrix, TargetStatus.OK, 600))
            controller.onCheck(state(whatsapp, TargetStatus.OK, 600))
        }
        await { notification(matrix, "slow") != null }
        assertNull(notification(whatsapp, "slow"))
        assertNotNull(AlertActionReceiver.muteNow(ctx, matrix))
        await { notification(matrix, "slow") == null }
        repeat(3) { controller.onCheck(state(whatsapp, TargetStatus.OK, 1_500)) }
        await { notification(whatsapp, "slow") != null }
    }

    @Test fun productionTargetPublicationAlertsWithoutWaitingForHistory() {
        val type = MonitorService::class.java
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val service = MonitorService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, ctx)
        val thread = HandlerThread("test-target-alert-owner").apply { start() }
        val worker = Handler(thread.looper)
        val session = HistorySessions.queue(ctx).open()
        val previousRunning = MonitorService.running
        val previousService = field("activeService").get(null)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { synchronized(History) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
        var monitoring: TargetMonitoring? = null
        try {
            field("worker").set(service, worker)
            field("historySession").setLong(service, session)
            field("targetAlertController").set(service, controller)
            field("activeService").set(null, service)
            field("running").setBoolean(null, true)
            monitoring = type.getDeclaredMethod("createTargetMonitoring").apply { isAccessible = true }
                .invoke(service) as TargetMonitoring
            field("targetMonitoring").set(service, monitoring)
            val host = TargetMonitoring::class.java.getDeclaredField("host").apply { isAccessible = true }
                .get(monitoring) as TargetMonitorHost
            holder.start()
            assertTrue(held.await(2, TimeUnit.SECONDS))
            worker.post { host.onPublished(state(matrix, TargetStatus.DOWN),
                TargetReport(TargetStatus.DOWN, emptyList(), true, null)) }
            await { notification(matrix, "outage") != null }
            assertEquals("Measured event publication must not wait for a history write", 1L, release.count)
        } finally {
            release.countDown()
            if (holder.isAlive) holder.join(5_000)
            monitoring?.close()
            worker.removeCallbacksAndMessages(null)
            thread.quitSafely(); thread.join(5_000)
            field("activeService").set(null, previousService)
            field("running").setBoolean(null, previousRunning)
            (field("probeExecutor").get(service) as ExecutorService).shutdownNow()
            (field("extraProbeExecutor").get(service) as ExecutorService).shutdownNow()
            (field("extraCoordinator").get(service) as ExtraProbeCoordinator).close()
            HistorySessions.queue(ctx).close(session, System.currentTimeMillis()).get()
        }
    }

    private fun state(id: TargetId, status: TargetStatus, latency: Long? = null) =
        TargetSnapshot(id, status, System.currentTimeMillis(), SystemClock.elapsedRealtime(), latencyMs = latency,
            endpointRevision = TargetPreferences.endpointRevision(ctx, id))
    private fun notification(id: TargetId, event: String): Notification? = manager.activeNotifications
        .firstOrNull { it.id == TargetEventNotifications.id(id, event) }?.notification
    private fun clearAll() {
        TargetId.entries.forEach { id -> listOf("outage", "partial", "recovery", "slow").forEach {
            manager.cancel(TargetEventNotifications.id(id, it))
        } }
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue("Target notification did not reach the expected state", condition())
    }
}
