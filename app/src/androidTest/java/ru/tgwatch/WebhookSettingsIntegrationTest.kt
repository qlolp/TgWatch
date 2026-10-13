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
    private var previous: Map<String, *>? = null
    private var previousEnabled: Boolean? = null

    private fun store() = ctx.getSharedPreferences(WebhookSettings.FILE, Context.MODE_PRIVATE)

    @Before fun clear() {
        previousEnabled = Prefs.isEnabled(ctx)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        assertFalse("The production monitor must stop before fixture credentials are installed", MonitorService.running)
        previous = store().all.toMap()
        assertTrue(WebhookSettings.clear(ctx))
    }

    @After fun restore() {
        try {
            previous?.let { saved ->
                synchronized(WebhookSettings) {
                    val invalidated = WebhookSettings.clear(ctx)
                    val edit = store().edit().clear()
                    saved.forEach { (key, value) ->
                        when (value) {
                            is String -> edit.putString(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            else -> error("Unsupported fixture preference")
                        }
                    }
                    val restored = edit.commit()
                    assertTrue("Original webhook preferences must be restored", restored)
                    assertTrue("Fixture settings must advance the configuration generation", invalidated)
                }
            }
        } finally {
            try {
                WebhookNotifier.reset()
            } finally {
                previousEnabled?.let { Prefs.setEnabled(ctx, it) }
            }
        }
    }

    @Test fun addressIsOptInValidatedPrivateAndExcludedFromBackupAndDiagnostics() {
        assertFalse("Off by default", WebhookSettings.enabled(ctx))
        assertNull(WebhookSettings.activeUrl(ctx))
        assertFalse("Cannot enable without an address", WebhookSettings.enable(ctx, true) == WebhookSettings.SaveResult.OK)
        assertFalse(WebhookSettings.save(ctx, "http://ntfy.sh/topic") == WebhookSettings.SaveResult.OK)
        assertFalse(WebhookSettings.save(ctx, "https://user:pw@ntfy.sh/topic") == WebhookSettings.SaveResult.OK)
        assertNull(WebhookSettings.url(ctx))

        assertTrue(WebhookSettings.save(ctx, " $secret ") == WebhookSettings.SaveResult.OK)
        assertEquals(secret, WebhookSettings.url(ctx))
        assertNull("Saved but still off", WebhookSettings.activeUrl(ctx))
        assertTrue(WebhookSettings.enable(ctx, true) == WebhookSettings.SaveResult.OK)
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

    @Test fun successfulSettingsWritesAdvanceTheGenerationButInvalidInputDoesNot() {
        val initial = WebhookSettings.configuration(ctx)
        assertTrue(WebhookSettings.save(ctx, secret) == WebhookSettings.SaveResult.OK)
        val saved = WebhookSettings.configuration(ctx)
        assertTrue(saved.generation > initial.generation)
        assertEquals(secret, saved.url)
        assertFalse(saved.enabled)
        assertFalse(WebhookSettings.save(ctx, "http://example.org/invalid") == WebhookSettings.SaveResult.OK)
        assertEquals(saved, WebhookSettings.configuration(ctx))
        assertTrue(WebhookSettings.enable(ctx, true) == WebhookSettings.SaveResult.OK)
        val enabled = WebhookSettings.configuration(ctx)
        assertTrue(enabled.generation > saved.generation)
        assertTrue(enabled.enabled)
        assertTrue(WebhookSettings.clear(ctx))
        val removed = WebhookSettings.configuration(ctx)
        assertTrue(removed.generation > enabled.generation)
        assertNull(removed.url)
        assertFalse(removed.enabled)
    }

    @Test fun coordinatorRejectsACapturedAddressAfterARealStorageWriteWithoutALaterReset() {
        val replacement = "https://example.org/replacement"
        assertTrue(WebhookSettings.save(ctx, secret) == WebhookSettings.SaveResult.OK)
        assertTrue(WebhookSettings.enable(ctx, true) == WebhookSettings.SaveResult.OK)
        var replaceAfterRead = true
        val posted = mutableListOf<String>()
        val coordinator = WebhookCoordinator(WebhookSettings, {
            WebhookSettings.configuration(ctx).also {
                if (replaceAfterRead) {
                    replaceAfterRead = false
                    assertTrue(WebhookSettings.save(ctx, replacement) == WebhookSettings.SaveResult.OK)
                }
            }
        }, { 0L }, { url, _, _ -> posted.add(url); true })

        coordinator.onPublished("TG_DOWN")
        assertTrue("The saved generation must invalidate the captured credential", posted.isEmpty())
        coordinator.onPublished("TG_DOWN")
        assertEquals(listOf(replacement), posted)
    }

    @Test fun realSettingsChangesKeepTheOldPhysicalRequestCountedAndTheNewHistoryFresh() {
        val replacement = "https://example.org/replacement"
        assertTrue(WebhookSettings.save(ctx, secret) == WebhookSettings.SaveResult.OK)
        assertTrue(WebhookSettings.enable(ctx, true) == WebhookSettings.SaveResult.OK)
        val posted = mutableListOf<String>()
        val completions = mutableListOf<(Int) -> Unit>()
        val coordinator = WebhookCoordinator(WebhookSettings, { WebhookSettings.configuration(ctx) }, { 0L },
            { url, _, finished -> posted.add(url); completions.add(finished); true })
        coordinator.onPublished("TG_DOWN")
        assertTrue(WebhookSettings.save(ctx, replacement) == WebhookSettings.SaveResult.OK)
        coordinator.onPublished("TG_DOWN")
        assertEquals(listOf(secret), posted)

        completions.single()(204)
        coordinator.onPublished("TG_DOWN")
        assertEquals(listOf(secret, replacement), posted)
        completions.first()(500)
        coordinator.onPublished("NO_NETWORK")
        assertEquals("Duplicate completion cannot release the replacement request", 2, posted.size)
        completions.last()(204)
    }
}
