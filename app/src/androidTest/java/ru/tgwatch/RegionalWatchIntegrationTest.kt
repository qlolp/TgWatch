package ru.tgwatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RegionalWatchIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val service = RegionalService.GEMINI
    private lateinit var preferences: Map<String, *>
    private lateinit var snapshot: TargetBackupSnapshot

    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        preferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        snapshot = BackupManager.snapshotTargets(ctx)
        val editor = Prefs.sp(ctx).edit().clear().putBoolean("enabled", true)
        TargetId.entries.forEach { editor.putBoolean(TargetPreferences.key(it, "enabled"), false) }
        check(editor.commit())
        assertTrue(RegionalPreferences.setEnabled(ctx, service, true))
        RegionalStateStore.clear()
    }

    @After fun restore() {
        if (!::preferences.isInitialized) return
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        BackupManager.restoreTargets(ctx, snapshot)
        val edit = Prefs.sp(ctx).edit().clear()
        preferences.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
        } }
        check(edit.commit())
        RegionalStateStore.clear()
        RegionalPublicNotifications(ctx).clear(service)
    }

    private fun result(status: RegionalStatus, elapsed: Long = SystemClock.elapsedRealtime(),
        confirmed: Boolean = false) = RegionalResult(service, status, System.currentTimeMillis(), elapsed,
        ctx.getSystemService(ConnectivityManager::class.java).activeNetwork!!.networkHandle, 1,
        RegionalPreferences.revision(ctx), if (status == RegionalStatus.WEB_REACHABLE) 200 else 403,
        regionConfirmed = confirmed)

    private fun arm(): RegionalWatchMetadata {
        val now = SystemClock.elapsedRealtime()
        assertTrue(RegionalWatchPreferences.arm(ctx, result(RegionalStatus.REGION_RESTRICTED,
            now - 30_000, true), now - 20_000))
        return RegionalWatchPreferences.current(ctx, service)!!
    }

    private fun allowNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        assertTrue(ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
    }

    @Test fun bootDisableAndRestoreCannotReviveAnOldArmedRequest() {
        assertFalse(RegionalWatchPreferences.arm(ctx, result(RegionalStatus.WEB_REACHABLE)))
        val old = arm()
        Prefs.sp(ctx).edit().putInt("regional.watch.gemini.boot", old.boot + 1).commit()
        assertNull(RegionalWatchPreferences.current(ctx, service))
        arm()
        assertTrue(RegionalPreferences.setEnabled(ctx, service, false))
        assertNull(RegionalWatchPreferences.current(ctx, service))
        assertTrue(RegionalPreferences.setEnabled(ctx, service, true))
        assertNull(RegionalWatchPreferences.current(ctx, service))
        arm()
        BackupManager.restoreTargets(ctx, snapshot)
        assertFalse(Prefs.sp(ctx).all.keys.any { it.startsWith("regional.watch.") })
    }

    @Test fun quietMuteMaintenanceAndMissingOrBlockedChannelDoNotConsumeTheGoal() {
        allowNotifications()
        val metadata = arm()
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val notification = result(RegionalStatus.WEB_REACHABLE)
        val calendar = java.util.Calendar.getInstance()
        val minute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        Prefs.setQuietHoursMinutes(ctx, (minute + 1439) % 1440, (minute + 2) % 1440)
        Prefs.setQuietHoursEnabled(ctx, true)
        assertFalse(RegionalPublicNotifications(ctx).post(notification, metadata))
        assertEquals(metadata, RegionalWatchPreferences.current(ctx, service))
        Prefs.setQuietHoursEnabled(ctx, false)
        assertTrue(Prefs.muteAlerts(ctx))
        assertFalse(RegionalPublicNotifications(ctx).post(notification, metadata))
        assertTrue(Prefs.clearAlertMute(ctx))
        MaintenanceWindows.save(ctx, RegionalPreferences.MAINTENANCE,
            listOf(MaintenanceWindow("watch-test", 0, 1440)))
        assertFalse(RegionalPublicNotifications(ctx).post(notification, metadata))
        MaintenanceWindows.save(ctx, RegionalPreferences.MAINTENANCE, emptyList())
        val channel = "regional_watch_blocked_test"
        manager.createNotificationChannel(NotificationChannel(channel, "Test", NotificationManager.IMPORTANCE_NONE))
        try {
            val blocked = RegionalPublicNotifications(ctx, channel)
            assertFalse(blocked.post(notification, metadata))
            manager.deleteNotificationChannel(channel)
            assertFalse(blocked.post(notification, metadata))
            assertEquals(metadata, RegionalWatchPreferences.current(ctx, service))
        } finally { manager.deleteNotificationChannel(channel) }
    }

    @Test fun aSuccessfulPublicNotificationConsumesOnceAndAnOldConsumeCannotClearAReplacement() {
        allowNotifications()
        val manager = ctx.getSystemService(NotificationManager::class.java)
        assertTrue(manager.areNotificationsEnabled())
        val old = arm()
        val generation = RegionalRepository.generation(ctx)
        val now = SystemClock.elapsedRealtime()
        lateinit var controller: RegionalWatchController
        instrumentation.runOnMainSync {
            controller = RegionalWatchController(ctx) { true }
            controller.observe(result(RegionalStatus.WEB_REACHABLE, now - 16_000), now, 600_000)
            controller.observe(result(RegionalStatus.WEB_REACHABLE, now), now, 600_000)
            controller.reconcile(result(RegionalStatus.WEB_REACHABLE, now), now, 600_000)
        }
        assertTrue(RegionalRepository.transaction(ctx, generation) { true }.get(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertNull(RegionalWatchPreferences.current(ctx, service))
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var posted = manager.activeNotifications.filter { it.notification.channelId == RegionalPublicNotifications.CHANNEL }
        while (posted.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(20)
            posted = manager.activeNotifications.filter { it.notification.channelId == RegionalPublicNotifications.CHANNEL }
        }
        assertEquals(1, posted.size)
        assertTrue(posted.single().notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(RegionalWatchPreferences.arm(ctx, result(RegionalStatus.REGION_RESTRICTED,
            SystemClock.elapsedRealtime(), true)))
        val replacement = RegionalWatchPreferences.current(ctx, service)
        assertNotEquals(old, replacement)
        assertFalse(RegionalWatchPreferences.consume(ctx, service, old))
        assertEquals(replacement, RegionalWatchPreferences.current(ctx, service))
        BackupManager.restoreTargets(ctx, snapshot)
        assertFalse(RegionalRepository.transaction(ctx, generation) {
            RegionalWatchPreferences.consume(ctx, service, old)
        }.get(10, TimeUnit.SECONDS))
    }

    @Test fun anExplicitNewRequestGetsItsOwnAlertWhileRetriesKeepOneStableCard() {
        allowNotifications()
        val manager = ctx.getSystemService(NotificationManager::class.java)
        assertTrue(manager.areNotificationsEnabled())
        val old = arm()
        val notifications = RegionalPublicNotifications(ctx)
        val response = result(RegionalStatus.WEB_REACHABLE)
        assertTrue(notifications.post(response, old))
        fun awaitRequest(armed: Long) {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            fun matches() = manager.activeNotifications.singleOrNull {
                it.notification.channelId == RegionalPublicNotifications.CHANNEL
            }?.notification?.extras?.getLong(RegionalPublicNotifications.REQUEST_ARMED, -1) == armed
            while (!matches() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertTrue("The card must belong to this exact request", matches())
        }
        awaitRequest(old.request.armedElapsed)
        assertTrue(notifications.post(response, old))
        awaitRequest(old.request.armedElapsed)
        val secondArm = SystemClock.elapsedRealtime() - 100
        assertTrue(RegionalWatchPreferences.arm(ctx, result(RegionalStatus.REGION_RESTRICTED,
            secondArm - 1, true), secondArm))
        val replacement = RegionalWatchPreferences.current(ctx, service)!!
        assertFalse(notifications.post(response, old))
        assertTrue(notifications.post(result(RegionalStatus.WEB_REACHABLE), replacement))
        awaitRequest(replacement.request.armedElapsed)
        assertFalse(notifications.post(response, old))
        awaitRequest(replacement.request.armedElapsed)
    }

    @Test fun deniedAppNotificationsDoNotConsumeTheArmedWatch() {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val manager = ctx.getSystemService(NotificationManager::class.java)
        // The separate permission-cycle smoke invokes this method after a real pm revoke.
        Assume.assumeFalse(manager.areNotificationsEnabled())
        val metadata = arm()
        assertFalse(RegionalPublicNotifications(ctx).post(result(RegionalStatus.WEB_REACHABLE), metadata))
        assertEquals(metadata, RegionalWatchPreferences.current(ctx, service))
    }
}
