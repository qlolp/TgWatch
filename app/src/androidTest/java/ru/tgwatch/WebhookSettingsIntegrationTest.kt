package ru.tgwatch

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The webhook address is a credential: opt-in, credential-protected and outside portable/diagnostic data. */
@RunWith(AndroidJUnit4::class)
class WebhookSettingsIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val secret = "https://ntfy.sh/private-topic-7f3a"
    private var previous: Map<String, *> = emptyMap<String, Any>()

    private fun store() = ctx.getSharedPreferences(WebhookSettings.FILE, Context.MODE_PRIVATE)

    @Before fun clear() {
        previous = store().all.toMap()
        assertTrue(store().edit().clear().commit())
    }

    @After fun restore() {
        val edit = store().edit().clear()
        previous.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                else -> error("Unsupported fixture preference")
            }
        }
        assertTrue(edit.commit())
        WebhookNotifier.reset()
    }

    @Test fun addressIsOptInValidatedPrivateAndExcludedFromBackupAndDiagnostics() {
        assertFalse("Off by default", WebhookSettings.enabled(ctx))
        assertNull(WebhookSettings.activeUrl(ctx))
        assertFalse("Cannot enable without an address", WebhookSettings.setEnabled(ctx, true))
        assertFalse(WebhookSettings.setUrl(ctx, "http://ntfy.sh/topic"))
        assertFalse(WebhookSettings.setUrl(ctx, "https://user:pw@ntfy.sh/topic"))
        assertNull(WebhookSettings.url(ctx))

        assertTrue(WebhookSettings.setUrl(ctx, " $secret "))
        assertEquals(secret, WebhookSettings.url(ctx))
        assertNull("Saved but still off", WebhookSettings.activeUrl(ctx))
        assertTrue(WebhookSettings.setEnabled(ctx, true))
        assertEquals(secret, WebhookSettings.activeUrl(ctx))
        assertEquals("Device-protected callers still read credential storage",
            secret, WebhookSettings.activeUrl(ctx.createDeviceProtectedStorageContext()))

        assertFalse("Never in Direct Boot storage", ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences(WebhookSettings.FILE, Context.MODE_PRIVATE).contains("url"))
        assertFalse(Prefs.sp(ctx).all.values.any { it.toString().contains("private-topic") })
        assertFalse(BackupManager.snapshot(ctx).settings.values.any { it.toString().contains("private-topic") })
        assertFalse(LocalDiagnostics.snapshot(ctx).toJson().contains("private-topic"))

        assertTrue(WebhookSettings.clear(ctx))
        assertFalse(WebhookSettings.enabled(ctx))
        assertNull(WebhookSettings.url(ctx))
    }
}
