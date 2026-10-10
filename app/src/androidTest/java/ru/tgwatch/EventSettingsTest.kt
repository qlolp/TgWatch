package ru.tgwatch

import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventSettingsTest {
    @Test fun readingSettingsNeverReplaysOrRemovesAPendingRestore() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Prefs.rawSp(ctx)
        val before = settings.all.toMap()
        val directory = ctx.createDeviceProtectedStorageContext().filesDir
        val files = listOf(File(directory, "restore.pending"), File(directory, "restore-pending-v1.json"))
        assertTrue("Fixture must not overwrite a real pending restore", files.none { it.exists() })
        val sentinel = "pending restore belongs to startup, not preference access".toByteArray()
        try {
            files.forEach { pending ->
                pending.writeBytes(sentinel)
                assertSame("Normal settings access must remain a raw accessor", settings, Prefs.sp(ctx))
                Prefs.quietStartMinute(ctx)
                Prefs.soundFor(ctx, "TG_DOWN")
                Prefs.notifyFor(ctx, "PARTIAL")
                assertEquals(before, settings.all)
                assertArrayEquals("A preference read must not consume the journal", sentinel, pending.readBytes())
                assertTrue("Both retained journal formats must block enabling", BackupManager.hasPending(ctx))
                Prefs.setEnabled(ctx, true)
                assertEquals("A pending restore must prevent changing enabled", before["enabled"], settings.all["enabled"])
                pending.delete()
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test fun independentEventOverridesAndLegacyFallbackCoexistWithMinuteHours() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Prefs.sp(ctx)
        val keys = listOf("event_sound", "sound_outage", "sound_partial", "sound_recovery",
            "notify_outage", "notify_partial", "notify_recovery", "quiet_start_hour", "quiet_end_hour",
            "quiet_start_minute", "quiet_end_minute")
        val previous = keys.associateWith { settings.all[it] }
        try {
            settings.edit().apply { keys.forEach { remove(it) }; putBoolean("event_sound", true) }.commit()
            assertTrue(Prefs.soundFor(ctx, "TG_DOWN"))
            assertTrue(Prefs.soundFor(ctx, "RECOVERY"))
            assertFalse("Legacy global sound must not opt in PARTIAL", Prefs.soundFor(ctx, "PARTIAL"))
            assertTrue(Prefs.notifyFor(ctx, "TG_DOWN"))
            assertTrue(Prefs.notifyFor(ctx, "RECOVERY"))
            assertFalse(Prefs.notifyFor(ctx, "PARTIAL"))
            Prefs.setSoundFor(ctx, "TG_DOWN", false)
            Prefs.setSoundFor(ctx, "PARTIAL", true)
            Prefs.setNotifyFor(ctx, "TG_DOWN", false)
            Prefs.setNotifyFor(ctx, "PARTIAL", true)
            Prefs.setQuietHoursMinutes(ctx, 1395, 525)
            assertFalse(Prefs.soundFor(ctx, "TG_DOWN"))
            assertTrue(Prefs.soundFor(ctx, "RECOVERY"))
            assertTrue(Prefs.soundFor(ctx, "PARTIAL"))
            assertFalse(Prefs.notifyFor(ctx, "TG_DOWN"))
            assertTrue(Prefs.notifyFor(ctx, "RECOVERY"))
            assertTrue(Prefs.notifyFor(ctx, "PARTIAL"))
            assertEquals(1395, Prefs.quietStartMinute(ctx))
            assertEquals(525, Prefs.quietEndMinute(ctx))
            assertEquals(23, Prefs.quietStartHour(ctx))
            assertEquals(8, Prefs.quietEndHour(ctx))
        } finally {
            settings.edit().apply {
                previous.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    else -> remove(key)
                } }
            }.commit()
        }
    }

    @Test fun recoveryAndSoundControlsPersistAndSelectedVibrationIsShown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val settings = Prefs.sp(ctx)
        val keys = listOf("enabled", "event_sound", "notify_recovery", "vibration_pattern", "sound_outage",
            "sound_recovery", "sound_partial", "notify_outage", "notify_partial")
        val previous = keys.associateWith { settings.all[it] }
        settings.edit().putBoolean("enabled", false).remove("event_sound").remove("notify_recovery")
            .remove("vibration_pattern").commit()
        settings.edit().remove("sound_outage").remove("sound_partial").remove("sound_recovery")
            .remove("notify_outage").remove("notify_partial").commit()
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.btnAdvanced).performClick()
                    val soundId = activity.resources.getIdentifier("swEventSound", "id", ctx.packageName)
                    assertTrue("Sound preference must be present", soundId != 0)
                    val sound = activity.findViewById<Switch>(soundId)
                    assertFalse(sound.isChecked)
                    sound.performClick()
                    val recoveryId = activity.resources.getIdentifier("swNotifyRecovery", "id", ctx.packageName)
                    val recovery = activity.findViewById<Switch>(recoveryId)
                    assertTrue(recovery.isChecked)
                    recovery.performClick()
                    assertTrue(Prefs.soundFor(ctx,"TG_DOWN"))
                    assertFalse(Prefs.soundFor(ctx,"RECOVERY"))
                    assertFalse(Prefs.notifyFor(ctx,"PARTIAL"))
                    activity.findViewById<Switch>(R.id.swSoundRecovery).performClick()
                    activity.findViewById<Switch>(R.id.swNotifyPartial).performClick()
                    assertTrue(Prefs.soundFor(ctx,"RECOVERY"))
                    assertTrue(Prefs.notifyFor(ctx,"PARTIAL"))
                    assertFalse(Prefs.soundFor(ctx,"PARTIAL"))
                    assertFalse(settings.getBoolean("notify_recovery", true))
                    settings.edit().putString("vibration_pattern", "SHORT").commit()
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val soundId = activity.resources.getIdentifier("swEventSound", "id", ctx.packageName)
                    assertTrue(activity.findViewById<Switch>(soundId).isChecked)
                    val recoveryId = activity.resources.getIdentifier("swNotifyRecovery", "id", ctx.packageName)
                    assertFalse(activity.findViewById<Switch>(recoveryId).isChecked)
                    assertTrue(activity.findViewById<Switch>(R.id.swSoundRecovery).isChecked)
                    assertTrue(activity.findViewById<Switch>(R.id.swNotifyPartial).isChecked)
                    val patternId = activity.resources.getIdentifier("btnAlarmPattern", "id", ctx.packageName)
                    assertTrue(activity.findViewById<Button>(patternId).text.contains("Короткий"))
                    // Capture real UI after assertions; documentation images come from the emulator.
                    activity.findViewById<Switch>(soundId).performClick()
                    activity.findViewById<Switch>(recoveryId).performClick()
                    activity.findViewById<Switch>(R.id.swSoundRecovery).performClick()
                    activity.findViewById<Switch>(R.id.swNotifyPartial).performClick()
                    val anchor = activity.findViewById<View>(patternId)
                    anchor.requestRectangleOnScreen(android.graphics.Rect(0, 0, anchor.width, anchor.height), true)
                }
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(200)
                // UTP removes the app/data after tests; preserve the image in the emulator's shell-owned directory.
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                    .executeShellCommand("screencap -p /data/local/tmp/tgwatch-settings.png")).use { it.readBytes() }
            }
        } finally {
            settings.edit().apply {
                previous.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    else -> remove(key)
                } }
            }.commit()
        }
    }
}
