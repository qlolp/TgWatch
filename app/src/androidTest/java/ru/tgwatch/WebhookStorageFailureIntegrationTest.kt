package ru.tgwatch

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Failed commit keeps Android's in-memory mutation, so admission must still invalidate old snapshots. */
@RunWith(AndroidJUnit4::class)
class WebhookStorageFailureIntegrationTest {
    @Test fun validAddressWithFailedDiskWriteIsAStorageFailureAndStillAdvancesGeneration() {
        val fixture = FailedPreferences()
        val before = WebhookSettings.configuration(fixture.context)
        val result = WebhookSettings.setUrlResult(fixture.context, fixture.address)
        val after = WebhookSettings.configuration(fixture.context)

        assertEquals(WebhookSettings.WriteResult.STORAGE_FAILED, result)
        assertTrue("A failed disk write can still change the in-process credential", before.url != after.url)
        assertTrue("Even a failed commit must invalidate captured credentials", after.generation > before.generation)
        assertEquals(WebhookSettings.WriteResult.STORAGE_FAILED,
            WebhookSettings.setEnabledResult(fixture.context, true))
    }

    @Test fun invalidInputDoesNotTouchStorageOrConfiguration() {
        val fixture = FailedPreferences()
        val before = WebhookSettings.configuration(fixture.context)

        assertEquals(WebhookSettings.WriteResult.INVALID,
            WebhookSettings.setUrlResult(fixture.context, "invalid"))
        assertEquals(WebhookSettings.WriteResult.INVALID,
            WebhookSettings.setEnabledResult(fixture.context, true))
        assertEquals(before, WebhookSettings.configuration(fixture.context))
        assertEquals(0, fixture.commits)
    }

    private class FailedPreferences {
        val address = "https://example.org/test-only"
        private val values = mutableMapOf<String, Any?>()
        var commits = 0
        private val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean", "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> null
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when {
                    method.name.startsWith("put") -> { changes[args!![0] as String] = args[1]; editor }
                    method.name == "remove" -> { changes[args!![0] as String] = null; editor }
                    method.name == "commit" -> {
                        commits++
                        values.putAll(changes)
                        false
                    }
                    else -> editor
                }
            } as SharedPreferences.Editor
            return editor
        }

        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
    }
}
