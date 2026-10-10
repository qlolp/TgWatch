package ru.tgwatch

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventChannelsTest {
    @Test fun partialAndRecoveryHaveIndependentAudibleChannels() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        EventNotifications(ctx,manager)
        assertNotNull("PARTIAL needs its own configurable channel",manager.getNotificationChannel("events_partial_sound_v1"))
        assertNotNull("Recovery needs its own configurable channel",manager.getNotificationChannel("events_recovery_sound_v1"))
    }
}
