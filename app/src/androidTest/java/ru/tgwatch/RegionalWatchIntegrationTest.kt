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

    private fun key(suffix: String) = "regional.watch.${service.wire}.$suffix"
    private fun stored(suffix: String) = Prefs.sp(ctx).getLong(key(suffix), -1)
    private fun watchKeys() = Prefs.sp(ctx).all.keys.filter { it.startsWith("regional.watch.") }
    private fun moveToAnotherBoot() =
        check(Prefs.sp(ctx).edit().putInt(key("boot"), Prefs.sp(ctx).getInt(key("boot"), 0) + 1).commit())

    @Test fun aRebootResumesOnlyTheRemainingTimeAndASecondRebootKeepsShrinkingIt() {
        val old = arm()
        val armedWall = stored("armed_wall")
        assertEquals(RegionalWatchRequest.TTL_MS, stored("deadline_wall") - armedWall)
        moveToAnotherBoot()
        assertNull(RegionalWatchPreferences.current(ctx, service))
        val first = SystemClock.elapsedRealtime()
        assertTrue(RegionalWatchClock.reconcile(ctx, now = first, wallNow = armedWall + 15_000))
        val resumed = RegionalWatchPreferences.current(ctx, service)!!
        assertTrue(resumed.restoredAfterBoot)
        assertNotEquals(old, resumed)
        assertEquals(RegionalWatchRequest(first, first + RegionalWatchRequest.TTL_MS - 15_000), resumed.request)
        assertFalse(RegionalWatchClock.reconcile(ctx, now = first, wallNow = armedWall + 15_000))
        assertEquals(resumed, RegionalWatchPreferences.current(ctx, service))

        moveToAnotherBoot()
        val second = SystemClock.elapsedRealtime()
        assertTrue(RegionalWatchClock.reconcile(ctx, now = second, wallNow = armedWall + 75_000))
        assertEquals(RegionalWatchRequest(second, second + RegionalWatchRequest.TTL_MS - 75_000),
            RegionalWatchPreferences.current(ctx, service)!!.request)
    }

    @Test fun aClockChangeBeforeTheRebootCannotStretchTheLifetime() {
        arm()
        val now = SystemClock.elapsedRealtime()
        val remaining = stored("deadline").let { it - now }
        val movedBack = System.currentTimeMillis() - 10 * 60 * 60_000L
        assertFalse(RegionalWatchClock.reconcile(ctx, now = now, wallNow = movedBack))
        assertEquals(movedBack + remaining, stored("deadline_wall"))
        moveToAnotherBoot()
        val later = SystemClock.elapsedRealtime()
        assertTrue(RegionalWatchClock.reconcile(ctx, now = later, wallNow = movedBack + 30_000))
        assertEquals(RegionalWatchRequest(later, later + remaining - 30_000),
            RegionalWatchPreferences.current(ctx, service)!!.request)
    }

    @Test fun aClockBehindTheArmingTimeKeepsTheRequestUntilTheClockSyncs() {
        arm()
        val armedWall = stored("armed_wall")
        moveToAnotherBoot()
        val keys = watchKeys().toSet()
        assertFalse(RegionalWatchClock.reconcile(ctx, wallNow = armedWall - 60_000))
        assertEquals(keys, watchKeys().toSet())
        assertNull(RegionalWatchPreferences.current(ctx, service))
        assertTrue(RegionalWatchClock.reconcile(ctx, wallNow = armedWall + 1_000))
        assertTrue(RegionalWatchPreferences.current(ctx, service)!!.restoredAfterBoot)
    }

    @Test fun aDisabledOrReEnabledProfileIsNotResumedAfterReboot() {
        arm()
        moveToAnotherBoot()
        assertTrue(RegionalPreferences.setEnabled(ctx, service, false))
        assertFalse(RegionalWatchClock.reconcile(ctx, wallNow = stored("armed_wall") + 1_000))
        assertTrue(watchKeys().isEmpty())

        assertTrue(RegionalPreferences.setEnabled(ctx, service, true))
        arm()
        moveToAnotherBoot()
        assertTrue(RegionalPreferences.setEnabled(ctx, service, false))
        assertTrue(RegionalPreferences.setEnabled(ctx, service, true))
        assertFalse(RegionalWatchClock.reconcile(ctx, wallNow = stored("armed_wall") + 1_000))
        assertTrue(watchKeys().isEmpty())
        assertNull(RegionalWatchPreferences.current(ctx, service))
    }

    @Test fun aWatchArmedBeforeWallAnchorsGainsThemInTheSameBootAndEndsOnReboot() {
        val old = arm()
        fun dropAnchors() = check(Prefs.sp(ctx).edit().remove(key("armed_wall")).remove(key("deadline_wall")).commit())
        dropAnchors()
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        assertFalse(RegionalWatchClock.reconcile(ctx, now = now, wallNow = wall))
        assertEquals(wall + old.request.deadlineElapsed - now, stored("deadline_wall"))
        assertEquals(RegionalWatchRequest.TTL_MS, stored("deadline_wall") - stored("armed_wall"))
        assertEquals(old, RegionalWatchPreferences.current(ctx, service))

        dropAnchors()
        moveToAnotherBoot()
        assertFalse(RegionalWatchClock.reconcile(ctx))
        assertTrue(watchKeys().isEmpty())
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
