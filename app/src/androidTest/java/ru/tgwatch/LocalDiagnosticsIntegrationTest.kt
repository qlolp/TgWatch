package ru.tgwatch

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDiagnosticsIntegrationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualSnapshotAndSafResultWriteBoundedRedactedJsonWithoutChangingMonitoring() {
        assertFalse("Diagnostics fixture requires a stopped monitor", MonitorService.running)
        val preferences = Prefs.sp(ctx)
        val originalPreferences = preferences.all.toMap()
        val automation = ctx.getSharedPreferences(AutomationSettings.FILE, Context.MODE_PRIVATE)
        val originalAutomation = automation.all.toMap()
        val originalLog = EventLog.all(ctx).toList()
        val originalState = MonitorService.state
        val stateField = MonitorService::class.java.getDeclaredField("state").apply { isAccessible = true }
        val destination = File.createTempFile("diagnostics-test-", ".json", ctx.cacheDir)
        val secret = "PRIVATE_TOKEN_PASSWORD account@example.org 192.168.9.4"
        val checkedAt = System.currentTimeMillis()
        try {
            // Seed the real backing stores, but bypass monitoring/history writes when seeding state.
            assertTrue(preferences.edit().putString("diagnostic_private_fixture", secret).commit())
            assertTrue(automation.edit().putString("token", secret).putBoolean("enabled", false).commit())
            EventLog.add(ctx, secret)
            stateField.set(null, originalState.copy(status = MonitorService.Status.OK, checkedAt = checkedAt,
                checkedElapsed = SystemClock.elapsedRealtime(), reason = secret, diagnostics = secret))

            val snapshot = LocalDiagnostics.snapshot(ctx)
            assertEquals(MonitorService.Status.OK, snapshot.status.status)
            assertEquals(checkedAt, snapshot.status.checkedAt)
            assertFalse(snapshot.running)
            assertTrue("A stopped monitor is stale even with a recent persisted check", snapshot.stale)
            val power = ctx.getSystemService(PowerManager::class.java)
            assertEquals(power.isPowerSaveMode, snapshot.powerSave)
            assertEquals(power.isDeviceIdleMode, snapshot.deviceIdleObserved)
            assertEquals(power.isIgnoringBatteryOptimizations(ctx.packageName), snapshot.batteryExempt)
            assertEquals(Build.VERSION.SDK_INT < 31 ||
                ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(), snapshot.exactAlarmsAllowed)
            assertEquals(ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED), snapshot.notificationsAllowed)

            ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val result = Intent().setData(Uri.fromFile(destination))
                    assertFalse(LocalDiagnostics.onActivityResult(activity, -1, Activity.RESULT_OK, result))
                    assertTrue(LocalDiagnostics.onActivityResult(activity, LocalDiagnostics.EXPORT_REQUEST_CODE,
                        Activity.RESULT_CANCELED, result))
                    assertEquals(0L, destination.length())
                    assertTrue(LocalDiagnostics.onActivityResult(activity, LocalDiagnostics.EXPORT_REQUEST_CODE,
                        Activity.RESULT_OK, result))
                }
                val deadline = SystemClock.elapsedRealtime() + 10_000
                var output: JSONObject? = null
                while (output == null && SystemClock.elapsedRealtime() < deadline) {
                    output = try { JSONObject(destination.readText()) } catch (_: Exception) { null }
                    if (output == null) SystemClock.sleep(25)
                }
                val json = checkNotNull(output) { "The SAF result handler did not write valid JSON" }
                assertTrue(destination.length() in 1..DiagnosticSnapshot.MAX_BYTES.toLong())
                val contents = destination.readText()
                listOf("PRIVATE_TOKEN_PASSWORD", "account@example.org", "192.168.9.4",
                    "diagnostic_private_fixture", "reason", "diagnostics", "log", "token")
                    .forEach { assertFalse(it, contents.contains(it)) }
                assertEquals(4, json.getInt("schema_version"))
                val goals = json.getJSONArray("targets")
                assertEquals(TargetId.entries.size, goals.length())
                assertEquals(TargetId.entries.map { it.wire }.toSet(),
                    (0 until goals.length()).map { goals.getJSONObject(it).getString("target_id") }.toSet())
                assertEquals(Build.VERSION.SDK_INT, json.getJSONObject("os").getInt("sdk_int"))
                assertEquals("OK", json.getJSONObject("state").getString("status"))
                assertEquals(checkedAt, json.getJSONObject("state").getLong("checked_at_ms"))
                assertTrue(json.getJSONObject("state").getBoolean("stale"))
                assertFalse(json.getJSONObject("state").getBoolean("running"))
                assertFalse(MonitorService.running)
                assertEquals(originalPreferences["enabled"], preferences.all["enabled"])
            }
        } finally {
            stateField.set(null, originalState)
            EventLog.replace(ctx, originalLog)
            restorePreferences(preferences, originalPreferences)
            restorePreferences(automation, originalAutomation)
            assertTrue(destination.delete())
        }
    }

    private fun restorePreferences(preferences: SharedPreferences, original: Map<String, *>) {
        val editor = preferences.edit().clear()
        original.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> error("Unsupported fixture preference type")
            }
        }
        assertTrue(editor.commit())
    }
}
