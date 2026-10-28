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

    @Before fun prepare() {
        previousWebhook = WebhookSettings.url(ctx)
        assertTrue(MqttSettings.clear(ctx))
    }

    @After fun restore() {
        MqttSettings.clear(ctx)
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
