package ru.tgwatch

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** MQTT is opt-in; broker, topic and credentials stay private and legacy webhook receiver zero is untouched. */
@RunWith(AndroidJUnit4::class)
class MqttSettingsIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val secrets = listOf("broker.secret-host.example", "secret/topic-7f3a", "secret-user", "secret-password")
    private var previousWebhook: String? = null
    private var previous: Map<String, *>? = null

    private fun store() = ctx.getSharedPreferences(MqttSettings.FILE, Context.MODE_PRIVATE)

    @Before fun prepare() {
        previousWebhook = WebhookSettings.url(ctx)
        previous = store().all.toMap()
        assertTrue(MqttSettings.clear(ctx))
    }

    /** Restores the complete previous private snapshot so a device's real settings survive the test. */
    @After fun restore() {
        val saved = previous ?: return
        synchronized(MqttSettings) {
            assertTrue("Fixture settings must advance the configuration generation", MqttSettings.clear(ctx))
            val edit = store().edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    else -> error("Unsupported fixture preference")
                }
            }
            assertTrue("Original MQTT preferences must be restored", edit.commit())
        }
        MqttNotifier.reset()
    }

    @Test fun passwordWithoutUsernameIsRejected() {
        assertEquals(WebhookSettings.WriteResult.INVALID,
            MqttSettings.setEndpointResult(ctx, secrets[0], "", secrets[1], "", secrets[3]))
        assertNull(MqttSettings.endpoint(ctx))
    }

    @Test fun storedPasswordWithoutUsernameFailsClosed() {
        assertTrue(store().edit().putString("host", secrets[0]).putInt("port", 8883)
            .putString("topic", secrets[1]).putString("password", secrets[3])
            .putBoolean("enabled", true).commit())

        assertNull(MqttSettings.endpoint(ctx))
        assertFalse(MqttSettings.enabled(ctx))
        assertNull(MqttSettings.configuration(ctx).url)
    }

    @Test fun offByDefaultValidatedPrivateAndOutsideBackupDiagnosticsAndPrefs() {
        assertFalse(MqttSettings.enabled(ctx))
        assertEquals(WebhookSettings.WriteResult.INVALID, MqttSettings.setEnabledResult(ctx, true))
        assertEquals(WebhookSettings.WriteResult.INVALID,
            MqttSettings.setEndpointResult(ctx, secrets[0], "8883", "a/#", "", ""))
        assertNull(MqttSettings.hostLabel(ctx))
        assertEquals(WebhookSettings.WriteResult.SAVED,
            MqttSettings.setEndpointResult(ctx, secrets[0], "", secrets[1], secrets[2], secrets[3]))
        assertEquals(WebhookSettings.WriteResult.SAVED, MqttSettings.setEnabledResult(ctx, true))
        assertTrue(MqttSettings.enabled(ctx))
        assertEquals(MqttPolicy.DEFAULT_PORT, MqttSettings.endpoint(ctx)?.port)
        assertFalse(ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences(MqttSettings.FILE, Context.MODE_PRIVATE).contains("host"))
        val portable = BackupManager.snapshot(ctx).settings.values.joinToString { it.toString() }
        val diagnostics = LocalDiagnostics.snapshot(ctx).toJson()
        secrets.forEach {
            assertFalse(Prefs.sp(ctx).all.values.any { value -> value.toString().contains(it) })
            assertFalse(portable.contains(it))
            assertFalse(diagnostics.contains(it))
        }
        assertEquals("Legacy receiver zero is unchanged", previousWebhook, WebhookSettings.url(ctx))
        assertTrue(MqttSettings.clear(ctx))
        assertFalse(MqttSettings.enabled(ctx))
        assertNull(MqttSettings.endpoint(ctx))
    }
}
