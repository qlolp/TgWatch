package ru.tgwatch

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import java.lang.reflect.Proxy
import androidx.test.core.app.ActivityScenario
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

@RunWith(AndroidJUnit4::class)
class CrashReportingSettingsIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val endpoint = "https://crash.private.example/SECRET_TOKEN"
    private var original: Map<String, *> = emptyMap()

    private fun store() = ctx.getSharedPreferences(CrashReportingSettings.FILE, Context.MODE_PRIVATE)

    @Before fun clearFixture() {
        original = store().all.toMap()
        assertTrue(CrashReportingSettings.clear(ctx))
    }

    @After fun restoreFixture() {
        val editor = store().edit().clear()
        original.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> error("Unsupported crash reporting preference fixture")
            }
        }
        assertTrue(editor.commit())
    }

    @Test fun settingIsOffByDefaultRequiresSeparateConsentAndCanBeDeleted() {
        assertFalse(CrashReportingSettings.enabled(ctx))
        assertNull(CrashReportingSettings.endpoint(ctx))
        assertNull(CrashReportingSettings.activeEndpoint(ctx))
        assertEquals(CrashReportingSettings.WriteResult.INVALID, CrashReportingSettings.setEnabled(ctx, true))
        assertEquals(CrashReportingSettings.WriteResult.INVALID,
            CrashReportingSettings.setEndpoint(ctx, "http://collector.example/crash"))

        assertEquals(CrashReportingSettings.WriteResult.SAVED, CrashReportingSettings.setEndpoint(ctx, endpoint))
        assertEquals(endpoint, CrashReportingSettings.endpoint(ctx))
        assertNull(CrashReportingSettings.activeEndpoint(ctx))
        assertFalse("Saving a URL never grants consent", CrashReportingSettings.enabled(ctx))
        assertEquals(CrashReportingSettings.WriteResult.SAVED, CrashReportingSettings.setEnabled(ctx, true))
        assertTrue(CrashReportingSettings.enabled(ctx))
        assertEquals(endpoint, CrashReportingSettings.activeEndpoint(ctx))
        assertFalse("Credentials stay out of device-protected preferences",
            ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(CrashReportingSettings.FILE, Context.MODE_PRIVATE).contains("endpoint"))
        assertFalse("Consent stays out of device-protected preferences",
            ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(CrashReportingSettings.FILE, Context.MODE_PRIVATE).contains("consent"))
        assertFalse("Credentials stay out of the ordinary monitor preferences",
            Prefs.sp(ctx).all.values.any { it.toString().contains("SECRET_TOKEN") })
        assertFalse("Credentials stay out of portable backup",
            BackupManager.snapshot(ctx).settings.values.any { it.toString().contains("SECRET_TOKEN") })
        assertFalse("The endpoint is not in explicit diagnostic export",
            LocalDiagnostics.snapshot(ctx).toJson().contains("SECRET_TOKEN"))

        assertTrue(CrashReportingSettings.clear(ctx))
        assertFalse(CrashReportingSettings.enabled(ctx))
        assertNull(CrashReportingSettings.endpoint(ctx))
        assertNull(CrashReportingSettings.activeEndpoint(ctx))
    }

    @Test fun endpointReplacementRevokesConsentAndSettingsNeverSaveEndpointInViewState() {
        assertEquals(CrashReportingSettings.WriteResult.SAVED, CrashReportingSettings.setEndpoint(ctx, endpoint))
        assertEquals(CrashReportingSettings.WriteResult.SAVED, CrashReportingSettings.setEnabled(ctx, true))
        assertEquals(CrashReportingSettings.WriteResult.SAVED,
            CrashReportingSettings.setEndpoint(ctx, "https://collector.private.example/new-secret"))
        assertFalse(CrashReportingSettings.enabled(ctx))

        ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue((activity.window.attributes.flags and
                    android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0)
                assertFalse(activity.findViewById<android.widget.Switch>(
                    AutomationSettingsActivity.ID_CRASH_REPORTING_ENABLE).isSaveEnabled)
                assertFalse(activity.findViewById<android.widget.TextView>(
                    AutomationSettingsActivity.ID_CRASH_REPORTING_ENDPOINT).text.contains("new-secret"))
                val toggle = activity.findViewById<android.widget.Switch>(
                    AutomationSettingsActivity.ID_CRASH_REPORTING_ENABLE)
                toggle.performClick()
                assertTrue("The consent switch explicitly enables sending", CrashReportingSettings.enabled(ctx))
                toggle.performClick()
                assertFalse("Turning consent off revokes sending", CrashReportingSettings.enabled(ctx))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<android.widget.TextView>(
                    AutomationSettingsActivity.ID_CRASH_REPORTING_ENDPOINT).text.contains("new-secret"))
                assertEquals("https://collector.private.example/new-secret",
                    CrashReportingSettings.endpoint(ctx))
                assertFalse(CrashReportingSettings.enabled(ctx))
            }
        }
    }

        @Test fun setEndpointButtonOpensSecureDialogAndSavesReplacementAddress() {
            val replacement = "https://collector.private.example/replaced"
            assertEquals(CrashReportingSettings.WriteResult.SAVED, CrashReportingSettings.setEndpoint(ctx, endpoint))
            assertEquals(CrashReportingSettings.WriteResult.SAVED, CrashReportingSettings.setEnabled(ctx, true))

            ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(AutomationSettingsActivity.ID_CRASH_REPORTING_SET).performClick()
                    val dialog = checkNotNull(activity.crashEndpointDialog)
                    assertTrue(dialog.isShowing)
                    assertTrue((dialog.window!!.attributes.flags and
                        android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0)
                    val input = dialog.findViewById<EditText>(AutomationSettingsActivity.ID_CRASH_REPORTING_INPUT)
                    assertTrue("The saved endpoint must not be prefilled", input.text.isEmpty())
                    input.setText(replacement)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(replacement, CrashReportingSettings.endpoint(ctx))
                assertFalse("Replacing the endpoint revokes prior consent", CrashReportingSettings.enabled(ctx))
            }
        }

        @Test fun failedClearCommitFailsClosedUntilASuccessfulPrivateWrite() {
            assertEquals(CrashReportingSettings.WriteResult.SAVED, CrashReportingSettings.setEndpoint(ctx, endpoint))
            assertEquals(CrashReportingSettings.WriteResult.SAVED, CrashReportingSettings.setEnabled(ctx, true))
            val backing = store()
            val failedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getBoolean", "getString" -> method.invoke(backing, *(args ?: emptyArray()))
                    "edit" -> {
                        val actual = backing.edit()
                        lateinit var editor: SharedPreferences.Editor
                        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                            arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                            when {
                                editMethod.name == "remove" -> { editMethod.invoke(actual, *(editArgs ?: emptyArray())); editor }
                                editMethod.name == "commit" -> false
                                else -> editor
                            }
                        } as SharedPreferences.Editor
                        editor
                    }
                    else -> method.invoke(backing, *(args ?: emptyArray()))
                }
            } as SharedPreferences
            val failingContext = object : ContextWrapper(ctx) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = failedPreferences
            }

            assertFalse(CrashReportingSettings.clear(failingContext))
            assertFalse("A failed clear must never leave the reporter visibly enabled",
                CrashReportingSettings.enabled(ctx))
            assertNull(CrashReportingSettings.activeEndpoint(ctx))
            assertEquals(CrashReportingSettings.WriteResult.SAVED,
                CrashReportingSettings.setEndpoint(ctx, "https://collector.example/new"))
            assertFalse("Recovery requires fresh consent", CrashReportingSettings.enabled(ctx))
        }
}
