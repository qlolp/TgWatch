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
            assertTrue(recovery.extras.getString(Notification.EXTRA_TEXT)!!.contains(recoveryDurationStr(1_044_000)))
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
                .extras.getString(Notification.EXTRA_TEXT)!!.contains(recoveryDurationStr(2000)))
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
        val previous = settings.all["alerts_muted_until"] as? Long
        val log = EventLog.all(ctx).toList()
        try {
            settings.edit().remove("alerts_muted_until").commit()
            assertEquals(0L, Prefs.alertsMutedUntil(ctx))
            EventNotifications(ctx, manager).outage("Test", sound = false, allowed = true)
            val outage = awaitNotification(manager, EventNotifications.OUTAGE_ID)
            val before = System.currentTimeMillis()
            outage.actions[1].actionIntent.send()
            awaitAbsent(manager, EventNotifications.OUTAGE_ID)
            val until = Prefs.alertsMutedUntil(ctx)
            assertTrue("Mute must last one hour from the tap", until >= before + AlertMute.DURATION_MS &&
                until <= System.currentTimeMillis() + AlertMute.DURATION_MS)
            assertFalse("Muted alerts use the quiet-hours suppression", Prefs.alertsAllowed(ctx))
            assertEquals(0L, Prefs.alertsMutedUntil(ctx, until))
        } finally {
            manager.cancel(EventNotifications.OUTAGE_ID)
            settings.edit().apply { if (previous == null) remove("alerts_muted_until") else putLong("alerts_muted_until", previous) }.commit()
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
            settings.edit().putBoolean("daily_summary", true).remove("daily_summary_last_at").commit()
            val alarm = android.content.Intent(ctx, DailySummaryReceiver::class.java)
                .setAction("ru.tgwatch.action.DAILY_SUMMARY")
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
            assertTrue(settings.edit().putBoolean("daily_summary", true)
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
