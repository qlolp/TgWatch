package ru.tgwatch

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlertMuteIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val muteKeys = listOf("alerts_muted_until", "alert_mute_boot", "alert_mute_started_elapsed",
        "alert_mute_until_elapsed")

    private fun withMuteFixture(block: () -> Unit) {
        val settings = Prefs.sp(ctx)
        val old = muteKeys.associateWith { settings.all[it] }
        assertTrue(Prefs.clearAlertMute(ctx))
        try {
            block()
        } finally {
            val edit = settings.edit()
            old.forEach { (key, value) -> when (value) {
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                else -> edit.remove(key)
            } }
            assertTrue(edit.commit())
        }
    }

    @Test fun elapsedTimerSurvivesStoreReopeningAndWallCorrections() = withMuteFixture {
        val now = System.currentTimeMillis()
        assertTrue(Prefs.muteAlerts(ctx, now))
        val dp = ctx.createDeviceProtectedStorageContext().getSharedPreferences("tgwatch", Context.MODE_PRIVATE)
        assertEquals(Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT),
            dp.getInt("alert_mute_boot", -1))
        val remaining = dp.getLong("alert_mute_until_elapsed", 0) - SystemClock.elapsedRealtime()
        assertTrue(remaining in 1..AlertMute.DURATION_MS)
        assertFalse(dp.contains("alerts_muted_until"))
        assertTrue(Prefs.alertsMutedUntil(ctx, now - 86_400_000) > now - 86_400_000)
        assertTrue(Prefs.alertsMutedUntil(ctx, now + 86_400_000) > now + 86_400_000)
        assertFalse(Prefs.alertsAllowed(ctx))
    }

    @Test fun expiredAndRebootedTimersAreRemovedAndDoNotRevive() = withMuteFixture {
        val settings = Prefs.sp(ctx)
        val now = System.currentTimeMillis()
        assertTrue(Prefs.muteAlerts(ctx))
        val deadline = SystemClock.elapsedRealtime()
        assertTrue(settings.edit().putLong("alert_mute_started_elapsed", (deadline - 1000).coerceAtLeast(0))
            .putLong("alert_mute_until_elapsed", deadline).commit())
        assertEquals(0L, Prefs.alertsMutedUntil(ctx, now))
        assertTrue(muteKeys.none { settings.contains(it) })
        assertEquals(0L, Prefs.alertsMutedUntil(ctx, now - 60_000))
        assertTrue(Prefs.muteAlerts(ctx))
        assertTrue(settings.edit().putInt("alert_mute_boot", settings.getInt("alert_mute_boot", -1) + 1).commit())
        assertEquals(0L, Prefs.alertsMutedUntil(ctx))
        assertTrue(muteKeys.none { settings.contains(it) })
    }

    @Test fun legacyIsAdoptedOnceAndCancelledWithoutChangingOtherSettings() = withMuteFixture {
        val settings = Prefs.sp(ctx)
        val preservedKeys = listOf("quiet_hours", "quiet_start_hour", "quiet_end_hour", "quiet_start_minute",
            "quiet_end_minute", "event_sound", "vibrate", "vibrate_partial", "vibrate_offline",
            "vibrate_recovery", "notify_recovery")
        val otherSettings = preservedKeys.associateWith { settings.all[it] }
        val now = System.currentTimeMillis()
        assertTrue(settings.edit().putLong("alerts_muted_until", now + 60_000).commit())
        val until = Prefs.alertsMutedUntil(ctx, now)
        assertTrue(until in (now + 59_000)..(now + 60_000))
        assertFalse(settings.contains("alerts_muted_until"))
        assertTrue(settings.contains("alert_mute_boot"))
        assertTrue(Prefs.alertsMutedUntil(ctx, now - 60_000) > now - 60_000)
        assertTrue(Prefs.clearAlertMute(ctx))
        assertEquals(0L, Prefs.alertsMutedUntil(ctx, now - 60_000))
        assertEquals(otherSettings, preservedKeys.associateWith { settings.all[it] })
    }

    @Test fun expiredLegacyIsClearedBeforeClockRollback() = withMuteFixture {
        val now = System.currentTimeMillis()
        assertTrue(Prefs.sp(ctx).edit().putLong("alerts_muted_until", now).commit())
        assertEquals(0L, Prefs.alertsMutedUntil(ctx, now))
        assertEquals(0L, Prefs.alertsMutedUntil(ctx, now - 60_000))
        assertTrue(muteKeys.none { Prefs.sp(ctx).contains(it) })
    }
}
