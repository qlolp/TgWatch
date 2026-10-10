package ru.tgwatch

import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventSettingsTest {
    @Test fun recoveryAndSoundControlsPersistAndSelectedVibrationIsShown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val settings = Prefs.sp(ctx)
        val keys = listOf("enabled", "event_sound", "notify_recovery", "vibration_pattern")
        val previous = keys.associateWith { settings.all[it] }
        settings.edit().putBoolean("enabled", false).remove("event_sound").remove("notify_recovery")
            .remove("vibration_pattern").commit()
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
                    assertTrue(settings.getBoolean("event_sound", false))
                    assertFalse(settings.getBoolean("notify_recovery", true))
                    settings.edit().putString("vibration_pattern", "SHORT").commit()
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val soundId = activity.resources.getIdentifier("swEventSound", "id", ctx.packageName)
                    assertTrue(activity.findViewById<Switch>(soundId).isChecked)
                    val recoveryId = activity.resources.getIdentifier("swNotifyRecovery", "id", ctx.packageName)
                    assertFalse(activity.findViewById<Switch>(recoveryId).isChecked)
                    val patternId = activity.resources.getIdentifier("btnAlarmPattern", "id", ctx.packageName)
                    assertTrue(activity.findViewById<Button>(patternId).text.contains("Короткий"))
                    // Capture real UI after assertions; documentation images come from the emulator.
                    activity.findViewById<Switch>(soundId).performClick()
                    activity.findViewById<Switch>(recoveryId).performClick()
                    val anchor = activity.findViewById<View>(recoveryId)
                    anchor.requestRectangleOnScreen(android.graphics.Rect(0, 0, anchor.width, anchor.height), true)
                }
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(200)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull(screenshot)
                val file = java.io.File(ctx.getExternalFilesDir("screenshots"), "settings.png")
                java.io.FileOutputStream(file).use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
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
