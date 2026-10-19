package ru.tgwatch

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventNotificationTest {
    @Test fun recoveryHasDurationAndQuietHoursForceSilentChannel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val events = EventNotifications(ctx, manager)
        try {
            events.recovered(RecoveryEvent(1_044_000), sound = true, allowed = false)
            val recovery = awaitNotification(manager, EventNotifications.RECOVERY_ID)
            // Event text follows the app language (Lang is set from resources, so en-US emulators see English).
            assertEquals(Lang.t("Telegram снова доступен", "Telegram is back"), recovery.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(recovery.extras.getString(Notification.EXTRA_TEXT)!!.contains(recoveryDurationStr(ctx, 1_044_000)))
            assertEquals(EventNotifications.SILENT_CHANNEL, recovery.channelId)
            assertNull("Recovery needs no check/mute actions", recovery.actions)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, recovery.groupAlertBehavior)
            assertNull(manager.getNotificationChannel(recovery.channelId).sound)
            events.outage("Контрольный сайт отвечает", sound = true, allowed = true)
            val outage = awaitNotification(manager, EventNotifications.OUTAGE_ID)
            assertEquals(EventNotifications.SOUND_CHANNEL, outage.channelId)
            assertEquals(listOf(Lang.t("Проверить сейчас", "Check now"), Lang.t("Заглушить на час", "Mute for 1 hour")),
                outage.actions.map { it.title.toString() })
            if (Build.VERSION.SDK_INT >= 31) assertTrue("Alert actions must not be mutable by other apps",
                outage.actions.all { it.actionIntent.isImmutable })
            assertNotNull(manager.getNotificationChannel(outage.channelId).sound)
            awaitAbsent(manager, EventNotifications.RECOVERY_ID)
            events.recovered(RecoveryEvent(2000), sound = false, allowed = true)
            assertTrue(awaitNotification(manager, EventNotifications.RECOVERY_ID)
                .extras.getString(Notification.EXTRA_TEXT)!!.contains(recoveryDurationStr(ctx, 2000)))
            awaitAbsent(manager, EventNotifications.OUTAGE_ID)
            events.partial("MTProto не отвечает",sound=true,allowed=true)
            assertEquals(EventNotifications.PARTIAL_CHANNEL,awaitNotification(manager,EventNotifications.PARTIAL_ID).channelId)
            awaitAbsent(manager,EventNotifications.RECOVERY_ID)
            events.recovered(RecoveryEvent(3000),sound=true,allowed=true)
            assertEquals(EventNotifications.RECOVERY_CHANNEL,awaitNotification(manager,EventNotifications.RECOVERY_ID).channelId)
            awaitAbsent(manager,EventNotifications.PARTIAL_ID)
            events.partial("В тихие часы",sound=true,allowed=false)
            val quietPartial = awaitNotification(manager,EventNotifications.PARTIAL_ID)
            assertEquals(EventNotifications.SILENT_CHANNEL,quietPartial.channelId)
            assertEquals(2, quietPartial.actions.size)
            assertEquals(Notification.GROUP_ALERT_SUMMARY,quietPartial.groupAlertBehavior)
        } finally {
            manager.cancel(EventNotifications.RECOVERY_ID)
            manager.cancel(EventNotifications.OUTAGE_ID)
            manager.cancel(EventNotifications.PARTIAL_ID)
        }
    }
    @Test fun muteActionSilencesAlertsForAnHourAndClearsTheAlert() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val settings = Prefs.sp(ctx)
        val muteKeys = listOf("alerts_muted_until", "alert_mute_boot", "alert_mute_started_elapsed",
            "alert_mute_until_elapsed")
        val previous = muteKeys.associateWith { settings.all[it] }
        val log = EventLog.all(ctx).toList()
        try {
            assertTrue(Prefs.clearAlertMute(ctx))
            assertEquals(0L, Prefs.alertsMutedUntil(ctx))
            EventNotifications(ctx, manager).outage("Test", sound = false, allowed = true)
            val outage = awaitNotification(manager, EventNotifications.OUTAGE_ID)
            EventNotifications(ctx, manager).slow(1500)
            awaitNotification(manager, EventNotifications.SLOW_ID)
            val before = System.currentTimeMillis()
            outage.actions[1].actionIntent.send()
            awaitAbsent(manager, EventNotifications.OUTAGE_ID)
            awaitAbsent(manager, EventNotifications.SLOW_ID)
            val until = Prefs.alertsMutedUntil(ctx)
            assertTrue("Mute must last one hour from the tap", until >= before + AlertMute.DURATION_MS &&
                until <= System.currentTimeMillis() + AlertMute.DURATION_MS)
            assertFalse("Muted alerts use the quiet-hours suppression", Prefs.alertsAllowed(ctx))
            assertTrue("A wall-clock jump must not expire the elapsed timer", Prefs.alertsMutedUntil(ctx, until) > until)
            assertTrue(Prefs.clearAlertMute(ctx))
            assertEquals(0L, Prefs.alertsMutedUntil(ctx))
        } finally {
            manager.cancel(EventNotifications.OUTAGE_ID)
            settings.edit().apply { previous.forEach { (key, value) -> when (value) {
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
                else -> remove(key)
            } } }.commit()
            EventLog.replace(ctx, log)
        }
    }

    @Test fun dailySummaryIsQuietAndOptIn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val settings = Prefs.sp(ctx)
        val previous = settings.all["daily_summary"] as? Boolean
        try {
            settings.edit().remove("daily_summary").commit()
            assertFalse("An upgrade must not enable the daily summary", Prefs.dailySummary(ctx))
            assertFalse(DailySummaryReceiver.post(ctx, force = false))
            assertEquals("Accepted delivery must return true", true,
                EventNotifications(ctx, manager).summary("Telegram за сутки: 100 %", "Сбоев Telegram не было"))
            val summary = awaitNotification(manager, EventNotifications.SUMMARY_ID)
            assertEquals(EventNotifications.SUMMARY_CHANNEL, summary.channelId)
            assertEquals("Telegram за сутки: 100 %", summary.extras.getString(Notification.EXTRA_TITLE))
            val channel = manager.getNotificationChannel(EventNotifications.SUMMARY_CHANNEL)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertNull(channel.sound)
            assertEquals("The summary must stay quiet even if its channel is customized",
                Notification.GROUP_ALERT_SUMMARY, summary.groupAlertBehavior)
            assertNotNull(summary.group)
        } finally {
            manager.cancel(EventNotifications.SUMMARY_ID)
            settings.edit().apply { if (previous == null) remove("daily_summary") else putBoolean("daily_summary", previous) }.commit()
            DailySummaryReceiver.schedule(ctx)
        }
    }
    // Run this case alone after revoking POST_NOTIFICATIONS, before starting
    // instrumentation. Revoking inside the test kills its own app process.
    @Test fun disabledAppNotificationsReportNoSummaryDelivery() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        org.junit.Assume.assumeFalse("Requires notifications disabled before instrumentation starts",
            manager.areNotificationsEnabled())
        assertFalse(EventNotifications(ctx, manager).summary("Summary", "Checks: 1"))
        assertTrue(manager.activeNotifications.none { it.id == EventNotifications.SUMMARY_ID })
    }

    @Test fun repeatedDailyAlarmPostsOnlyOnceButManualPreviewStillWorks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val settings = Prefs.sp(ctx)
        val original = settings.all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        val history = History.snapshot(ctx)
        try {
            val now = System.currentTimeMillis()
            History.replaceSnapshot(ctx, listOf(Observation(now - 60_000, now, "OK", 100)))
            settings.edit().putBoolean("daily_summary", true)
                .putBoolean(TargetPreferences.key(TargetId.TELEGRAM, "enabled"), true)
                .remove("daily_summary_last_at").commit()
            val alarm = android.content.Intent(ctx, DailySummaryReceiver::class.java)
                .setAction("ru.tgwatch.action.DAILY_SUMMARY")
            manager.cancel(EventNotifications.SUMMARY_ID)
            awaitAbsent(manager, EventNotifications.SUMMARY_ID)
            DailySummaryReceiver().onReceive(ctx, alarm)
            android.os.SystemClock.sleep(250)
            assertTrue("No scheduled summary while monitoring is off",
                manager.activeNotifications.none { it.id == EventNotifications.SUMMARY_ID })
            // The service stays stopped; only the setting says monitoring is on.
            assertTrue(settings.edit().putBoolean("enabled", true).commit())
            DailySummaryReceiver().onReceive(ctx, alarm)
            awaitNotification(manager, EventNotifications.SUMMARY_ID)
            manager.cancel(EventNotifications.SUMMARY_ID)
            awaitAbsent(manager, EventNotifications.SUMMARY_ID)
            DailySummaryReceiver().onReceive(ctx, alarm)
            android.os.SystemClock.sleep(250)
            assertTrue("Repeated alarm must not recreate the daily notification",
                manager.activeNotifications.none { it.id == EventNotifications.SUMMARY_ID })
            assertTrue("Manual preview remains available after today's scheduled summary",
                DailySummaryReceiver.post(ctx, force = true))
            awaitNotification(manager, EventNotifications.SUMMARY_ID)
        } finally {
            manager.cancel(EventNotifications.SUMMARY_ID)
            History.replaceSnapshot(ctx, history)
            val edit = settings.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            } }
            assertTrue(edit.commit())
            DailySummaryReceiver.schedule(ctx)
            MonitorService.clearRestoredState()
        }
    }

    @Test fun futureClockCorrectionDoesNotSuppressDifferentDaySummary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val settings = Prefs.sp(ctx)
        val original = settings.all.toMap()
        val log = EventLog.all(ctx).toList()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        val history = History.snapshot(ctx)
        try {
            val now = System.currentTimeMillis()
            History.replaceSnapshot(ctx, listOf(Observation(now - 60_000, now, "OK", 100)))
            assertTrue(settings.edit().putBoolean("daily_summary", true).putBoolean("enabled", true)
                .putBoolean(TargetPreferences.key(TargetId.TELEGRAM, "enabled"), true)
                .putLong("daily_summary_last_at", now + 9 * 86_400_000L).commit())
            assertTrue("A clock correction to a different day must not suppress summaries for nine days",
                DailySummaryReceiver.post(ctx, force = false))
            awaitNotification(manager, EventNotifications.SUMMARY_ID)
        } finally {
            manager.cancel(EventNotifications.SUMMARY_ID)
            History.replaceSnapshot(ctx, history)
            EventLog.replace(ctx, log)
            val edit = settings.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            } }
            assertTrue(edit.commit())
            DailySummaryReceiver.schedule(ctx)
            MonitorService.clearRestoredState()
        }
    }

    @Test fun slowTelegramNoticeIsQuietAndHasItsOwnChannel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val events = EventNotifications(ctx, manager)
        try {
            events.slow(1500)
            val slow = awaitNotification(manager, EventNotifications.SLOW_ID)
            assertEquals(EventNotifications.SLOW_CHANNEL, slow.channelId)
            assertEquals(Lang.t("Telegram тормозит", "Telegram is slow"), slow.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(slow.extras.getString(Notification.EXTRA_TEXT)!!.contains("1500"))
            val channel = manager.getNotificationChannel(EventNotifications.SLOW_CHANNEL)
            assertTrue("A slow Telegram is not an alarm", channel.importance <= NotificationManager.IMPORTANCE_LOW)
            assertNull(channel.sound)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, slow.groupAlertBehavior)
            events.clearSlow()
            awaitAbsent(manager, EventNotifications.SLOW_ID)
        } finally {
            manager.cancel(EventNotifications.SLOW_ID)
        }
    }

    @Test fun slowControllerReconcilesDisableMuteAndStopWithoutNewMeasurements() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val events = EventNotifications(ctx, manager)
        val settings = Prefs.sp(ctx)
        val keys = listOf("notify_slow", "quiet_hours", "alerts_muted_until", "alert_mute_boot",
            "alert_mute_started_elapsed", "alert_mute_until_elapsed")
        val previous = keys.associateWith { settings.all[it] }
        val originalLog = EventLog.all(ctx).toList()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        var controller: SlowAlertController? = null
        fun showSlow() {
            controller?.close()
            val current = SlowAlertController(ctx, events, running::get, {})
            controller = current
            // A synthetic now + index can still be in the future on a fast
            // emulator. Future measurements are deliberately stale.
            repeat(3) {
                current.onCheck("OK", 1500, android.os.SystemClock.elapsedRealtime())
            }
            awaitNotification(manager, EventNotifications.SLOW_ID)
        }
        try {
            Prefs.setNotifyFor(ctx, "SLOW", true)
            Prefs.setQuietHoursEnabled(ctx, false)
            assertTrue(Prefs.clearAlertMute(ctx))
            showSlow()
            Prefs.setNotifyFor(ctx, "SLOW", false)
            awaitAbsent(manager, EventNotifications.SLOW_ID)
            Prefs.setNotifyFor(ctx, "SLOW", true)
            showSlow()
            AlertActionReceiver.muteNow(ctx)
            awaitAbsent(manager, EventNotifications.SLOW_ID)
            controller?.close()
            assertTrue(Prefs.clearAlertMute(ctx))
            showSlow()
            running.set(false)
            controller!!.reconcile()
            awaitAbsent(manager, EventNotifications.SLOW_ID)
            assertTrue("Notification changes must not invent measurement recovery events",
                EventLog.all(ctx).none { it.contains(Lang.t("Telegram больше не тормозит", "Telegram is no longer slow")) &&
                    it !in originalLog })
        } finally {
            controller?.close()
            val edit = settings.edit()
            keys.forEach { key ->
                when (val value = previous[key]) {
                    null -> edit.remove(key)
                    is Boolean -> edit.putBoolean(key, value)
                    is Long -> edit.putLong(key, value)
                    is Int -> edit.putInt(key, value)
                }
            }
            assertTrue(edit.commit())
            EventLog.replace(ctx, originalLog)
            events.clearSlow()
        }
    }

    private fun awaitNotification(manager: NotificationManager, id: Int): Notification {
        repeat(40) {
            manager.activeNotifications.firstOrNull { it.id == id }?.let { return it.notification }
            android.os.SystemClock.sleep(50)
        }
        throw AssertionError("Notification $id was not posted")
    }
    private fun awaitAbsent(manager: NotificationManager, id: Int) {
        repeat(40) {
            if (manager.activeNotifications.none { it.id == id }) return
            android.os.SystemClock.sleep(50)
        }
        throw AssertionError("Notification $id was not cancelled")
    }
}
