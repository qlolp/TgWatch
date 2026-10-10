package ru.tgwatch

import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventChannelsTest {
    private val outage = "test_outage_${java.util.UUID.randomUUID()}"
    private val partial = "test_partial_${java.util.UUID.randomUUID()}"
    private val recovery = "test_recovery_${java.util.UUID.randomUUID()}"
    private fun events(ctx: android.content.Context, manager: NotificationManager) = EventNotifications(ctx,manager,outage,partial,recovery)
    @Test fun outageImportanceDoesNotChangeExistingRecoveryChannel() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        resetChannels(manager)
        try {
            events(ctx,manager)
            val original = manager.getNotificationChannel(recovery).importance
            manager.createNotificationChannel(NotificationChannel(outage,"Outage",NotificationManager.IMPORTANCE_LOW))
            assertEquals(NotificationManager.IMPORTANCE_LOW,manager.getNotificationChannel(outage).importance)
            events(ctx,manager)
            assertEquals("Recovery importance must be independent",original,
                manager.getNotificationChannel(recovery).importance)
        } finally { resetChannels(manager) }
    }
    @Test fun blockedEventChannelsCannotBeBypassedBySilentDelivery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
            .executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        val manager = ctx.getSystemService(NotificationManager::class.java)
        resetChannels(manager)
        try {
            val events = events(ctx,manager)
            val cases = listOf(
                Triple(outage,EventNotifications.OUTAGE_ID, { sound: Boolean, allowed: Boolean -> events.outage("Test",sound,allowed) }),
                Triple(partial,EventNotifications.PARTIAL_ID, { sound: Boolean, allowed: Boolean -> events.partial("Test",sound,allowed) }),
                Triple(recovery,EventNotifications.RECOVERY_ID, { sound: Boolean, allowed: Boolean -> events.recovered(RecoveryEvent(1000),sound,allowed) }))
            for ((channel,id,post) in cases) {
                manager.createNotificationChannel(NotificationChannel(channel,"Blocked",NotificationManager.IMPORTANCE_NONE))
                assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel(channel).importance)
                post(true,false)
                android.os.SystemClock.sleep(250)
                assertTrue("Quiet hours bypassed blocked $channel",manager.activeNotifications.none { it.id == id })
                post(false,true)
                android.os.SystemClock.sleep(250)
                assertTrue("Sound toggle bypassed blocked $channel",manager.activeNotifications.none { it.id == id })
                if (id == EventNotifications.OUTAGE_ID) {
                    events.partial("Enabled category",true,true)
                    repeat(40) { if (manager.activeNotifications.none { it.id == EventNotifications.PARTIAL_ID }) android.os.SystemClock.sleep(50) }
                    assertTrue("Unrelated enabled events must still work",manager.activeNotifications.any { it.id == EventNotifications.PARTIAL_ID })
                    manager.cancel(EventNotifications.PARTIAL_ID)
                }
            }
        } finally { resetChannels(manager) }
    }
    private fun resetChannels(manager: NotificationManager) {
        manager.cancel(EventNotifications.OUTAGE_ID); manager.cancel(EventNotifications.PARTIAL_ID); manager.cancel(EventNotifications.RECOVERY_ID)
        listOf(outage,partial,recovery)
            .forEach { manager.deleteNotificationChannel(it) }
    }
    @Test fun partialAndRecoveryHaveIndependentAudibleChannels() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        EventNotifications(ctx,manager)
        assertNotNull("PARTIAL needs its own configurable channel",manager.getNotificationChannel("events_partial_sound_v1"))
        assertNotNull("Recovery needs its own configurable channel",manager.getNotificationChannel("events_recovery_sound_v1"))
    }
}
