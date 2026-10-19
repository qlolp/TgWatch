package ru.tgwatch

import android.Manifest
import android.os.Build
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual dialogs must follow their resource Context, independent of the removed legacy process flag. */
@RunWith(AndroidJUnit4::class)
class AndroidCopyIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private lateinit var saved: Map<String, *>

    @Before fun prepare() {
        HistoryTestStorage.awaitReady()
        saved = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        Onboarding.markSeen(ctx)
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun restore() {
        if (::saved.isInitialized) restore(Prefs.sp(ctx), saved)
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }
    @Test fun profileDialogUsesItsContextEvenIfALegacyFlagIsStale() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val russian = ctx.getString(R.string.lang) == "ru"
            val undo = staleLegacyFlag(!russian)
            try {
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.btnAdvanced).performClick()
                    activity.findViewById<Button>(R.id.btnProfile).performClick()
                }
                val name = AndroidText.resolve(ctx, PowerProfile.ECONOMY.title)
                awaitVisible(if (russian) "$name: 60 с / сон 300 с / сбой 60 с"
                    else "$name: 60 s / sleep 300 s / outage 60 s")
            } finally { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); undo() }
        }
    }
    @Test fun backupDialogUsesItsContextEvenIfALegacyFlagIsStale() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val russian = ctx.getString(R.string.lang) == "ru"
            val undo = staleLegacyFlag(!russian)
            try {
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.btnAdvanced).performClick()
                    activity.findViewById<Button>(R.id.btnBackup).performClick()
                }
                awaitVisible(if (russian) "Формат резервной копии" else "Backup format")
                awaitVisible(if (russian) "Все цели — v3" else "All targets — v3")
            } finally { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); undo() }
        }
    }
    private fun awaitVisible(expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var found = false
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            found = root?.findAccessibilityNodeInfosByText(expected)?.any { it.text?.toString() == expected } == true
            if (!found) SystemClock.sleep(50)
        } while (!found && SystemClock.elapsedRealtime() < deadline)
        assertTrue("Expected translated dialog text: $expected", found)
    }
    private fun staleLegacyFlag(value: Boolean): () -> Unit {
        // Keep this regression executable after Lang is removed; it still checks actual dialog text.
        return try {
            val type = Class.forName("ru.tgwatch.Lang")
            val instance = type.getField("INSTANCE").get(null)
            val previous = type.getMethod("getRu").invoke(instance)
            val setter = type.getMethod("setRu", Boolean::class.javaPrimitiveType)
            val restore: () -> Unit = { setter.invoke(instance, previous); Unit }
            setter.invoke(instance, value)
            restore
        } catch (_: ClassNotFoundException) { {} }
    }
    private fun restore(store: SharedPreferences, values: Map<String, *>) {
        val edit = store.edit().clear()
        for ((key, value) in values) when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        assertTrue(edit.commit())
    }
}
