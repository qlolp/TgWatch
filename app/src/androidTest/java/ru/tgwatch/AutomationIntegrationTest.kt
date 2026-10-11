package ru.tgwatch

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AutomationIntegrationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousEnabled = false
    @Before fun prepare() {
        previousEnabled = Prefs.isEnabled(ctx)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        ctx.getSharedPreferences(AutomationSettings.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun clean() {
        AutomationSettings.setEnabled(ctx, false)
        Prefs.setEnabled(ctx, previousEnabled)
    }

    @Test fun credentialsAreOptInPrivateAndOutsidePortableBackup() {
        assertFalse(AutomationSettings.enabled(ctx))
        assertNull(AutomationSettings.token(ctx))
        AutomationSettings.setEnabled(ctx, true)
        val first = AutomationSettings.token(ctx)!!
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertFalse(ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences(AutomationSettings.FILE, Context.MODE_PRIVATE).contains("token"))
        assertTrue("Device-protected callers must still use ordinary credentials",
            first == AutomationSettings.token(ctx.createDeviceProtectedStorageContext()))
        assertFalse(BackupManager.snapshot(ctx).settings.values.contains(first))
        val second = AutomationSettings.regenerate(ctx)!!
        assertTrue("Regeneration must replace the token", first != second)
        assertEquals(AutomationReceiver.RESULT_UNAUTHORIZED, request(AutomationReceiver.ACTION_GET_STATE, first).first)
        AutomationSettings.setEnabled(ctx, false)
        assertNull(AutomationSettings.token(ctx))
        AutomationSettings.setEnabled(ctx, true)
        assertTrue("Re-enabling must create a new token", second != AutomationSettings.token(ctx))
    }

    @Test fun runningChecksAreThrottledAndPendingRestoreCannotRestartMonitor() {
        AutomationSettings.setEnabled(ctx, true)
        val token = AutomationSettings.token(ctx)!!
        Prefs.setEnabled(ctx, true)
        MonitorService.start(ctx)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (!MonitorService.running && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(20)
        }
        assertTrue("Monitor did not enter running state", MonitorService.running)
        try {
            assertEquals(AutomationReceiver.RESULT_OK, request(AutomationReceiver.ACTION_CHECK_NOW, token).first)
            assertEquals(AutomationReceiver.RESULT_RATE_LIMITED, request(AutomationReceiver.ACTION_CHECK_NOW, token).first)
            val nextToken = AutomationSettings.regenerate(ctx)!!
            assertEquals(AutomationReceiver.RESULT_RATE_LIMITED, request(AutomationReceiver.ACTION_CHECK_NOW, nextToken).first)
            Prefs.setEnabled(ctx, false)
            assertEquals(AutomationReceiver.RESULT_STOPPED, request(AutomationReceiver.ACTION_CHECK_NOW, nextToken).first)
            MonitorService.stopAndAwait(ctx)
            assertFalse(MonitorService.running)
            val journal = java.io.File(ctx.createDeviceProtectedStorageContext().filesDir, "restore.pending")
            assertFalse("Existing restore journal must be preserved", journal.exists())
            journal.writeText("test")
            try {
                assertEquals(AutomationReceiver.RESULT_STOPPED, request(AutomationReceiver.ACTION_CHECK_NOW, nextToken).first)
                assertFalse(MonitorService.running)
                assertFalse(Prefs.isEnabled(ctx))
            } finally {
                assertTrue(journal.delete())
            }
        } finally {
            Prefs.setEnabled(ctx, false)
            MonitorService.stopAndAwait(ctx)
        }
    }

    @Test fun unauthorizedRequestsDiscloseNoStateAndStoppedCheckCannotStartService() {
        val disabled = request(AutomationReceiver.ACTION_GET_STATE, "00".repeat(32))
        assertEquals(AutomationReceiver.RESULT_UNAUTHORIZED, disabled.first)
        assertTrue(disabled.second == null || disabled.second!!.isEmpty)
        AutomationSettings.setEnabled(ctx, true)
        val token = AutomationSettings.token(ctx)!!
        assertEquals(AutomationReceiver.RESULT_UNAUTHORIZED,
            request(AutomationReceiver.ACTION_GET_STATE, "0".repeat(10000)).first)
        assertEquals(AutomationReceiver.RESULT_INVALID, request("ru.tgwatch.action.START", token).first)
        val stopped = request(AutomationReceiver.ACTION_CHECK_NOW, token)
        assertEquals(AutomationReceiver.RESULT_STOPPED, stopped.first)
        assertFalse(MonitorService.running)
        assertFalse(Prefs.isEnabled(ctx))
        val state = request(AutomationReceiver.ACTION_GET_STATE, token)
        assertEquals(AutomationReceiver.RESULT_OK, state.first)
        assertEquals(setOf("status", "checked_at_ms", "stale", "running", "api_version"), state.second!!.keySet())
        assertFalse(state.second!!.getBoolean("running"))
        assertTrue(state.second!!.getBoolean("stale"))
        assertEquals(1, state.second!!.getInt("api_version"))
        assertFalse(MonitorService.running)
    }

    @Test fun screenCopiesOnlyOnDemandAndDoesNotSaveToken() {
        ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
            // ActivityScenario's RESUMED state can precede focus; Android denies clipboard reads then.
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var focused = false
            while (!focused && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { focused = it.hasWindowFocus() }
                if (!focused) android.os.SystemClock.sleep(50)
            }
            assertTrue("Automation screen must have focus before copying", focused)
            scenario.onActivity { activity ->
                val toggle = activity.findViewById<Switch>(AutomationSettingsActivity.ID_ENABLE)
                val copy = activity.findViewById<Button>(AutomationSettingsActivity.ID_COPY)
                assertFalse(toggle.isChecked)
                assertFalse(copy.isEnabled)
                toggle.performClick()
                val token = AutomationSettings.token(ctx)!!
                copy.performClick()
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                val clip = clipboard.primaryClip
                assertNotNull("Copy must populate the clipboard while focused", clip)
                assertTrue("Copied token must match", token == clip!!.getItemAt(0).text.toString())
                if (Build.VERSION.SDK_INT >= 33) assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
                val saved = Bundle()
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, saved)
                val parcel = android.os.Parcel.obtain()
                val bytes = try { parcel.writeBundle(saved); parcel.marshall() } finally { parcel.recycle() }
                assertFalse("Token must not appear in saved state", String(bytes, Charsets.UTF_8).contains(token))
                assertFalse("Token must not appear in saved view state", String(bytes, Charsets.UTF_16LE).contains(token))
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertFalse(activity.findViewById<View>(AutomationSettingsActivity.ID_TOKEN).isSaveEnabled)
                toggle.performClick()
                assertFalse(copy.isEnabled)
                assertNull(AutomationSettings.token(ctx))
                assertNull("Disabling must clear this screen's clipboard token", clipboard.primaryClip)
            }
            scenario.recreate()
            scenario.onActivity { assertFalse(it.findViewById<Switch>(AutomationSettingsActivity.ID_ENABLE).isChecked) }
        }
    }

    private fun request(action: String, token: String): Pair<Int, Bundle?> {
        val latch = CountDownLatch(1)
        var answer: Pair<Int, Bundle?>? = null
        ctx.sendOrderedBroadcast(Intent(ctx, AutomationReceiver::class.java).setAction(action)
            .putExtra(AutomationReceiver.EXTRA_TOKEN, token), null, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                answer = resultCode to getResultExtras(false)
                latch.countDown()
            }
        }, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
        assertTrue("Ordered automation result timed out", latch.await(5, TimeUnit.SECONDS))
        return answer!!
    }
}
