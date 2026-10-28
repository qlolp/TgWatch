package ru.tgwatch

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.tgwatch.probe.HttpJsonCondition
import ru.tgwatch.probe.HttpProbeCondition
import ru.tgwatch.probe.HttpRequestHeader

@RunWith(AndroidJUnit4::class)
class CustomHealthIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val id = TargetId.CUSTOM
    private val address = "https://private.example/private-path"
    private val condition = HttpProbeCondition(204, "private-field", "private-value", "body-secret",
        listOf(HttpJsonCondition("nested.path", "json-secret")),
        listOf(HttpRequestHeader("X-Private-Header", "header-secret")))
    private val credentials get() = ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)
    private lateinit var backup: TargetBackupSnapshot
    private lateinit var settings: Map<String, *>
    private lateinit var privateSettings: Map<String, *>

    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        settings = Prefs.sp(ctx).all.toMap()
        privateSettings = credentials.all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        backup = BackupManager.snapshotTargets(ctx)
        Onboarding.markSeen(ctx)
        assertTrue(Prefs.sp(ctx).edit().putBoolean(TargetPreferences.key(id, "endpoint_review"), false).commit())
        assertTrue(credentials.edit().putString(id.wire, address).commit())
    }
    @After fun restore() {
        TargetConfigTransactions.retry(ctx, id)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (::backup.isInitialized) BackupManager.restoreTargets(ctx, backup)
        if (::privateSettings.isInitialized) restore(credentials, privateSettings)
        if (::settings.isInitialized) restore(Prefs.sp(ctx), settings)
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }

    @Test fun conditionOnlyChangePublishesRevisionAndKeepsBothBackupFormatsPrivate() {
        val revision = TargetPreferences.endpointRevision(ctx, id)
        assertTrue(TargetSettingWrites.endpoint(ctx, id, address, condition = condition))
        assertEquals(revision + 1, TargetPreferences.endpointRevision(ctx, id))
        assertEquals(PrivateProbeConfig(address, condition), TargetPreferences.configuration(ctx, id))
        assertEquals(address, TargetPreferences.endpoint(ctx, id))
        assertFalse(TargetPreferences.definition(ctx, id).enabled)
        val exports = listOf(BackupCodec.encode(BackupManager.snapshot(ctx)).toString(Charsets.UTF_8),
            TargetBackupCodec.encode(BackupManager.snapshotTargets(ctx)).toString(Charsets.UTF_8),
            LocalDiagnostics.snapshot(ctx).toJson(),
            WebhookPayload.encode(WebhookEvent(WebhookEventKind.TEST, null, null), WebhookFormat.JSON)
                .bytes.toString(Charsets.UTF_8),
            StatusShareDocument.prepare(ctx, StatusShareSnapshot.capture(ctx, id)).text)
        for (value in exports) for (secret in listOf(address, "private-field", "private-value", "body-secret",
            "nested.path", "json-secret", "X-Private-Header", "header-secret"))
            assertFalse("Private configuration escaped an export allowlist", value.contains(secret))
        assertTrue(TargetSettingWrites.endpoint(ctx, id, address))
        assertNull(TargetPreferences.configuration(ctx, id)!!.condition)
    }
    @Test fun failedPrivateWriteReplaysTheWholeConditionWithIdOnlyPublicMarker() {
        val failOnce = AtomicBoolean(true)
        val wrapped = intercept { editor -> editor.commit() && !failOnce.getAndSet(false) }
        assertFalse(TargetSettingWrites.endpoint(wrapped, id, address, condition = condition))
        assertTrue(TargetConfigTransactions.isPending(id))
        assertNull(TargetPreferences.configuration(ctx, id))
        val marker = File(ctx.createDeviceProtectedStorageContext().filesDir, "target-config-custom-pending")
        assertTrue(marker.readText().matches(Regex("[a-f0-9]{32}")))
        for (secret in listOf("private-field", "private-value", "body-secret", "nested.path", "json-secret",
            "X-Private-Header", "header-secret")) assertFalse(marker.readText().contains(secret))
        assertTrue(TargetConfigTransactions.retry(ctx, id))
        assertFalse(TargetConfigTransactions.isPending(id))
        assertEquals(PrivateProbeConfig(address, condition), TargetPreferences.configuration(ctx, id))
    }
    @Test fun stopDuringConditionWriteStillWins() {
        assertTrue(Prefs.sp(ctx).edit().putBoolean("enabled", true)
            .putBoolean(TargetPreferences.key(id, "enabled"), true).commit())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wrapped = intercept { editor -> editor.commit().also {
            entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS))
        } }
        val thread = Thread { TargetSettingWrites.endpoint(wrapped, id, address, condition = condition) }
        thread.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            TargetPreferences.stop(ctx, id)
        } finally { release.countDown(); thread.join(15_000) }
        assertFalse(thread.isAlive)
        assertFalse(TargetPreferences.definition(ctx, id).enabled)
        assertEquals(condition, TargetPreferences.configuration(ctx, id)!!.condition)
    }
    @Test fun oldMatrixPrivateAddressRemainsReadableAndDoesNotAcceptHealthCriteria() {
        assertTrue(credentials.edit().putString(TargetId.MATRIX.wire, "https://matrix.example").commit())
        assertTrue(Prefs.sp(ctx).edit().putBoolean(TargetPreferences.key(TargetId.MATRIX, "endpoint_review"), false).commit())
        assertEquals("https://matrix.example", TargetPreferences.endpoint(ctx, TargetId.MATRIX))
        assertFalse(TargetSettingWrites.endpoint(ctx, TargetId.MATRIX, "https://matrix.example", condition = condition))
    }
    @Test fun healthInputsArePrivateAndInvalidSaveDoesNotDismissTheDialog() {
        assertTrue(TargetSettingWrites.endpoint(ctx, id, address, condition = condition))
        val inputs = AtomicReference<List<EditText>>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = TargetSettingsDialog.show(activity, id)
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val code = dialog.findViewById<EditText>(R.id.targetHealthCodeInput)
                val field = dialog.findViewById<EditText>(R.id.targetHealthFieldInput)
                val value = dialog.findViewById<EditText>(R.id.targetHealthValueInput)
                val body = dialog.findViewById<EditText>(R.id.targetHealthBodyInput)
                val headers = dialog.findViewById<EditText>(R.id.targetHealthHeadersInput)
                val extraField = dialog.findViewById<EditText>(R.id.targetHealthField2Input)
                val extraValue = dialog.findViewById<EditText>(R.id.targetHealthValue2Input)
                inputs.set(listOf(code, field, value, extraField, extraValue, body, headers))
                for (input in listOf(code, field, value)) {
                    assertTrue(input.text.isEmpty())
                    assertFalse(input.isSaveEnabled)
                    assertFalse(input.isSaveFromParentEnabled)
                    assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.importantForAutofill)
                }
                code.setText("999")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertNotNull(code.error)
                code.setText("204"); field.setText("private-field"); value.setText("private-value")
                body.setText("body-secret"); headers.setText("X-Private-Header: header-secret")
                dialog.dismiss()
            }
            // Android posts OnDismissListener to the main queue; verify after that delivery.
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(inputs.get().all { it.text.isEmpty() }) }
        }
    }
    @Test fun blankEditorPreservesCriteriaAndExplicitClearKeepsTheAddress() {
        assertTrue(TargetSettingWrites.endpoint(ctx, id, address, condition = condition))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                TargetSettingsDialog.show(activity, id).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            awaitSettings()
            assertEquals(condition, TargetPreferences.configuration(ctx, id)!!.condition)
            scenario.onActivity { activity ->
                val dialog = TargetSettingsDialog.show(activity, id)
                dialog.findViewById<CheckBox>(R.id.targetHealthClear).isChecked = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            awaitSettings()
            assertEquals(address, TargetPreferences.endpoint(ctx, id))
            assertNull(TargetPreferences.configuration(ctx, id)!!.condition)
        }
    }
    @Test fun settingsEditorStoresBodyMultipleJsonChecksAndHeadersPrivately() {
        val headerValue = "test-header-value"
        val saved = HttpProbeCondition(jsonField = "status", expectedValue = "ok", bodySubstring = "service online",
            jsonConditions = listOf(HttpJsonCondition("nested.ready", "yes")),
            headers = listOf(HttpRequestHeader("Authorization", headerValue)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = TargetSettingsDialog.show(activity, id)
                dialog.findViewById<EditText>(R.id.targetHealthFieldInput).setText("status")
                dialog.findViewById<EditText>(R.id.targetHealthValueInput).setText("ok")
                dialog.findViewById<EditText>(R.id.targetHealthField2Input).setText("nested.ready")
                dialog.findViewById<EditText>(R.id.targetHealthValue2Input).setText("yes")
                dialog.findViewById<EditText>(R.id.targetHealthBodyInput).setText("service online")
                dialog.findViewById<EditText>(R.id.targetHealthHeadersInput)
                    .setText("Authorization: $headerValue")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            awaitSettings()
            assertEquals(PrivateProbeConfig(address, saved), TargetPreferences.configuration(ctx, id))
            val credential = credentials.getString(id.wire, "").orEmpty()
            val privateValues = listOf("service online", "nested.ready", "Authorization", headerValue)
            for (secret in privateValues)
                assertTrue(credential.contains(secret))
            for (value in listOf(BackupCodec.encode(BackupManager.snapshot(ctx)).toString(Charsets.UTF_8),
                TargetBackupCodec.encode(BackupManager.snapshotTargets(ctx)).toString(Charsets.UTF_8),
                LocalDiagnostics.snapshot(ctx).toJson())) {
                for (secret in privateValues)
                    assertFalse(value.contains(secret))
            }
        }
    }
    private fun awaitSettings() {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (TargetSettingsExecutor.busy(id) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertFalse(TargetSettingsExecutor.busy(id))
    }

    private fun intercept(commit: (SharedPreferences.Editor) -> Boolean): Context = object : ContextWrapper(ctx) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val store = super.getSharedPreferences(name, mode)
            if (name != TargetPreferences.PRIVATE_FILE) return store
            return object : SharedPreferences by store {
                override fun edit(): SharedPreferences.Editor {
                    val edit = store.edit()
                    return object : SharedPreferences.Editor by edit {
                        override fun putString(key: String, value: String?): SharedPreferences.Editor {
                            edit.putString(key, value); return this
                        }
                        override fun commit() = commit(edit)
                    }
                }
            }
        }
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
