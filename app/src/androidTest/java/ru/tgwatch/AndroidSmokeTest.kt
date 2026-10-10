package ru.tgwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.os.Build
import android.view.View
import android.widget.Switch
import androidx.test.core.app.ActivityScenario

@RunWith(AndroidJUnit4::class)
class AndroidSmokeTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun advancedSettingsStartCollapsedAndPartialPreferenceSurvivesRecreation() {
        val settings = Prefs.sp(ctx)
        val wasEnabled = Prefs.isEnabled(ctx)
        settings.edit().putBoolean("enabled", false).remove("vibrate_partial").commit()
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val panelId = activity.resources.getIdentifier("advancedSettings", "id", ctx.packageName)
                    assertTrue("Advanced settings panel must exist", panelId != 0)
                    val panel = activity.findViewById<View>(panelId)
                    assertEquals(View.GONE, panel.visibility)
                    assertTrue(activity.findViewById<View>(R.id.btnProfile).isShown)
                    val toggleId = activity.resources.getIdentifier("btnAdvanced", "id", ctx.packageName)
                    activity.findViewById<View>(toggleId).performClick()
                    assertEquals(View.VISIBLE, panel.visibility)
                    val partialId = activity.resources.getIdentifier("swVibratePartial", "id", ctx.packageName)
                    val partial = activity.findViewById<Switch>(partialId)
                    assertFalse(partial.isChecked)
                    partial.performClick()
                    assertTrue(Prefs.sp(ctx).getBoolean("vibrate_partial", false))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val panelId = activity.resources.getIdentifier("advancedSettings", "id", ctx.packageName)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(panelId).visibility)
                    val partialId = activity.resources.getIdentifier("swVibratePartial", "id", ctx.packageName)
                    assertTrue(activity.findViewById<Switch>(partialId).isChecked)
                    val toggleId = activity.resources.getIdentifier("btnAdvanced", "id", ctx.packageName)
                    activity.findViewById<View>(toggleId).performClick()
                    assertEquals(View.GONE, activity.findViewById<View>(panelId).visibility)
                }
            }
        } finally {
            settings.edit().putBoolean("enabled", wasEnabled).remove("vibrate_partial").commit()
        }
    }
    @Test fun historyUsesDeviceProtectedStorageAndExports() {
        History.record(ctx, System.currentTimeMillis(), History.Kind.PARTIAL, 30, 30)
        val file = File(ctx.createDeviceProtectedStorageContext().filesDir, "history-v2.csv")
        assertTrue(file.exists())
        assertTrue(History.exportCsv(ctx).startsWith("from_utc,"))
        assertTrue(History.exportCsv(ctx).contains("PARTIAL"))
    }
    @Test fun profilesAndDiagnosticStateSurvivePreferencesReload() {
        Prefs.setProfile(ctx, PowerProfile.ECONOMY)
        assertEquals(300, Prefs.effectiveIntervalSec(ctx, false, true))
        val now = System.currentTimeMillis()
        Prefs.saveLastState(ctx, MonitorService.Status.PARTIAL, now, now, 10, "partial", 0, "Wi-Fi test", 60)
        val restored = Prefs.loadLastState(ctx)!!
        assertEquals(MonitorService.Status.PARTIAL, restored.status)
        assertEquals("Wi-Fi test", restored.diagnostics)
        assertEquals(60, restored.expectedIntervalSec)
        Prefs.setProfile(ctx, PowerProfile.BALANCED)
    }
}
