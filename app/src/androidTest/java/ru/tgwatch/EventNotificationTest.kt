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
            assertEquals("Telegram снова доступен", recovery.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(recovery.extras.getString(Notification.EXTRA_TEXT)!!.contains("17 мин 24 с"))
            assertEquals(EventNotifications.SILENT_CHANNEL, recovery.channelId)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, recovery.groupAlertBehavior)
            assertNull(manager.getNotificationChannel(recovery.channelId).sound)
            events.outage("Контрольный сайт отвечает", sound = true, allowed = true)
            val outage = awaitNotification(manager, EventNotifications.OUTAGE_ID)
            assertEquals(EventNotifications.SOUND_CHANNEL, outage.channelId)
            assertNotNull(manager.getNotificationChannel(outage.channelId).sound)
            awaitAbsent(manager, EventNotifications.RECOVERY_ID)
            events.recovered(RecoveryEvent(2000), sound = false, allowed = true)
            assertTrue(awaitNotification(manager, EventNotifications.RECOVERY_ID)
                .extras.getString(Notification.EXTRA_TEXT)!!.contains("2 с"))
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
            assertEquals(Notification.GROUP_ALERT_SUMMARY,quietPartial.groupAlertBehavior)
        } finally {
            manager.cancel(EventNotifications.RECOVERY_ID)
            manager.cancel(EventNotifications.OUTAGE_ID)
            manager.cancel(EventNotifications.PARTIAL_ID)
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
