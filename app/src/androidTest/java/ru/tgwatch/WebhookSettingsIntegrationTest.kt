package ru.tgwatch

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
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
                            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
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

    @Test fun successfulSettingsWritesAdvanceTheGenerationButInvalidInputDoesNot() {
        val initial = WebhookSettings.configuration(ctx)
        assertTrue(WebhookSettings.setUrl(ctx, secret))
        val saved = WebhookSettings.configuration(ctx)
        assertTrue(saved.generation > initial.generation)
        assertEquals(secret, saved.url)
        assertFalse(saved.enabled)
        assertFalse(WebhookSettings.setUrl(ctx, "http://example.org/invalid"))
        assertEquals(saved, WebhookSettings.configuration(ctx))
        assertTrue(WebhookSettings.setEnabled(ctx, true))
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
        assertTrue(WebhookSettings.setUrl(ctx, secret))
        assertTrue(WebhookSettings.setEnabled(ctx, true))
        var replaceAfterRead = true
        val posted = mutableListOf<String>()
        val coordinator = WebhookCoordinator(WebhookSettings, {
            WebhookSettings.configuration(ctx).also {
                if (replaceAfterRead) {
                    replaceAfterRead = false
                    assertTrue(WebhookSettings.setUrl(ctx, replacement))
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
        assertTrue(WebhookSettings.setUrl(ctx, secret))
        assertTrue(WebhookSettings.setEnabled(ctx, true))
        val posted = mutableListOf<String>()
        val completions = mutableListOf<(Int) -> Unit>()
        val coordinator = WebhookCoordinator(WebhookSettings, { WebhookSettings.configuration(ctx) }, { 0L },
            { url, _, finished -> posted.add(url); completions.add(finished); true })
        coordinator.onPublished("TG_DOWN")
        assertTrue(WebhookSettings.setUrl(ctx, replacement))
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
    @Test fun formatAndFiltersArePrivateAndSurviveScreenRecreation() {
        val before = BackupManager.snapshot(ctx).settings
        assertEquals(WebhookFormat.TEXT, WebhookSettings.configuration(ctx).format)
        assertEquals(WebhookEventKind.DELIVERY_EVENTS,
            WebhookSettings.configuration(ctx).events)
        assertEquals(WebhookSettings.WriteResult.SAVED, WebhookSettings.setDeliveryResult(ctx,
            WebhookFormat.JSON, setOf(WebhookEventKind.RECOVERY)))
        val saved = WebhookSettings.configuration(ctx)
        assertEquals(WebhookFormat.JSON, saved.format)
        assertEquals(setOf(WebhookEventKind.RECOVERY), saved.events)
        assertEquals(before, BackupManager.snapshot(ctx).settings)
        val direct = ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences(WebhookSettings.FILE, Context.MODE_PRIVATE)
        assertFalse(direct.contains("format"))
        assertFalse(direct.contains("events"))
        ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<Button>(AutomationSettingsActivity.ID_WEBHOOK_FORMAT)
                assertTrue(button.text.contains("JSON"))
                assertFalse(button.isSaveEnabled)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(AutomationSettingsActivity.ID_WEBHOOK_FORMAT)
                    .text.contains("JSON"))
            }
        }
        assertEquals(saved, WebhookSettings.configuration(ctx))
        assertEquals(WebhookSettings.WriteResult.INVALID, WebhookSettings.setDeliveryResult(ctx,
            WebhookFormat.TEXT, setOf(WebhookEventKind.TEST)))
        assertEquals(saved, WebhookSettings.configuration(ctx))
        assertTrue(WebhookSettings.clear(ctx))
        assertEquals(WebhookFormat.TEXT, WebhookSettings.configuration(ctx).format)
    }

    @Test fun heartbeatFormatTestMessageCannotClaimLiveMeasurementAndStaysOptIn() {
        assertTrue(WebhookSettings.setUrl(ctx, secret))
        val previous = WebhookSettings.configuration(ctx)
        assertEquals(WebhookSettings.WriteResult.SAVED, WebhookSettings.setDeliveryResult(ctx,
            WebhookFormat.JSON_V2, WebhookEventKind.DELIVERY_EVENTS))
        val current = WebhookSettings.configuration(ctx)
        assertTrue(current.generation > previous.generation)
        assertFalse(current.enabled)
        assertNull(WebhookSettings.activeUrl(ctx))
        val sent = mutableListOf<org.json.JSONObject>()
        val coordinator = WebhookCoordinator(Any(), { WebhookSettings.configuration(ctx) }, { 0L },
            { _, body, done -> sent.add(org.json.JSONObject(body.bytes.toString(Charsets.UTF_8))); done(204); true },
            schedule = { _, _ -> {} })
        val result = mutableListOf<WebhookNotifier.TestResult>()
        coordinator.sendTest(result::add)
        assertEquals(listOf(WebhookNotifier.TestResult.SENT), result)
        assertEquals("test", sent.single().getString("event"))
        assertFalse(sent.single().getBoolean("monitoring"))
        assertTrue(sent.single().getBoolean("is_stale"))
        assertTrue(sent.single().isNull("checked_at_ms"))
        assertEquals(11, sent.single().length())
        assertFalse(WebhookSettings.enabled(ctx))
    }
    @Test fun explicitFormatChooserShowsAndSavesAllThreeActualChoices() {
        ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = AutomationSettingsActivity::class.java.getDeclaredField("webhookDelivery")
                    .apply { isAccessible = true }
                val helper = field.get(activity) as WebhookDeliverySettingsUi
                val dialog = helper.chooseFormat()
                assertTrue(dialog.isShowing)
                val list = dialog.listView
                assertNotNull("Format list must be attached, not hidden behind an AlertDialog message", list.parent)
                assertEquals(3, list.adapter.count)
                assertEquals(activity.getString(R.string.webhook_format_json_v2), list.adapter.getItem(2))
                list.performItemClick(list.getChildAt(2), 2, list.adapter.getItemId(2))
                assertEquals(WebhookFormat.JSON_V2, WebhookSettings.configuration(ctx).format)
                assertFalse(WebhookSettings.enabled(ctx))
            }
        }
    }

    @Test fun heartbeatCannotBeInjectedAsAnEventFilter() {
        val previous = WebhookSettings.configuration(ctx)
        assertEquals(WebhookSettings.WriteResult.INVALID, WebhookSettings.setDeliveryResult(ctx,
            WebhookFormat.TEXT, setOf(WebhookEventKind.HEARTBEAT)))
        assertEquals(previous, WebhookSettings.configuration(ctx))
    }

    @Test fun targetSelectionIsPrivateVersionedAndDoesNotEnableMonitoring() {
        val initial = WebhookSettings.configuration(ctx)
        assertEquals(WebhookStatuses.services, initial.services)
        val targetDefinitions = TargetPreferences.definitions(ctx)
        assertEquals(WebhookSettings.WriteResult.SAVED, WebhookSettings.setTargetsResult(ctx, setOf("matrix")))
        val selected = WebhookSettings.configuration(ctx)
        assertEquals(setOf("matrix"), selected.services)
        assertTrue(selected.generation > initial.generation)
        assertFalse(selected.enabled)
        assertEquals(targetDefinitions, TargetPreferences.definitions(ctx))
        assertFalse(ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences(WebhookSettings.FILE, Context.MODE_PRIVATE).contains("services"))
        assertEquals(WebhookSettings.WriteResult.INVALID, WebhookSettings.setTargetsResult(ctx, setOf("private-endpoint")))
        assertEquals(selected, WebhookSettings.configuration(ctx))
        assertEquals(WebhookSettings.WriteResult.SAVED, WebhookSettings.setTargetsResult(ctx, emptySet()))
        assertTrue(WebhookSettings.configuration(ctx).services.isEmpty())
        assertTrue(WebhookSettings.clear(ctx))
        assertEquals(WebhookStatuses.services, WebhookSettings.configuration(ctx).services)
    }

    @Test fun targetChooserSavesOnlyFixedPublicGoalIds() {
        ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
            val shown = java.util.concurrent.atomic.AtomicReference<android.app.AlertDialog>()
            scenario.onActivity { activity ->
                val helper = AutomationSettingsActivity::class.java.getDeclaredField("webhookDelivery")
                    .apply { isAccessible = true }.get(activity) as WebhookDeliverySettingsUi
                shown.set(helper.chooseTargets())
            }
            // AlertDialog applies the initial multi-choice check states as rows are rendered.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val dialog = shown.get()
                assertTrue(dialog.isShowing)
                assertEquals(4, dialog.listView.adapter.count)
                TargetSlots.visible(activity).forEachIndexed { index, id ->
                    assertEquals(TargetNames.label(activity, id), dialog.listView.adapter.getItem(index))
                    assertTrue("The actual initial checkbox must be rendered", dialog.listView.isItemChecked(index))
                    if (id != TargetId.MATRIX) {
                        val row = dialog.listView.getChildAt(index - dialog.listView.firstVisiblePosition)
                        assertNotNull("Click a rendered checkbox", row)
                        dialog.listView.performItemClick(row, index, dialog.listView.adapter.getItemId(index))
                        assertFalse(dialog.listView.isItemChecked(index))
                    }
                }
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            // AlertDialog queues its positive callback on the main looper; do not
            // assert persistence until the actual save callback has executed.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                try {
                    assertEquals(setOf("matrix"), WebhookSettings.configuration(ctx).services)
                    assertEquals(activity.getString(R.string.webhook_targets_current, 1, 4),
                        activity.findViewById<Button>(AutomationSettingsActivity.ID_WEBHOOK_TARGETS).text.toString())
                    assertFalse(WebhookSettings.enabled(ctx))
                } finally {
                    shown.get().dismiss()
                }
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.webhook_targets_current, 1, 4),
                    activity.findViewById<Button>(AutomationSettingsActivity.ID_WEBHOOK_TARGETS).text.toString())
            }
        }
    }

    @Test fun failedSaveInvalidatesInMemoryGeneration() {
        assertTrue(WebhookSettings.setUrl(ctx, secret))
        assertTrue(WebhookSettings.setEnabled(ctx, true))
        val completions = mutableListOf<(Int) -> Unit>()
        val coordinator = WebhookCoordinator(WebhookSettings, { WebhookSettings.configuration(ctx) }, { 0L },
            { _, _, finished -> completions.add(finished); true })
        coordinator.onPublished(WebhookObservation("telegram", "TG_DOWN", 1_000, null,
            NetworkKind.UNKNOWN, 1, true, false))
        val before = WebhookSettings.configuration(ctx)
        val failing = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = ctx.getSharedPreferences(name, mode)
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value); return this
                            }
                            override fun putStringSet(key: String, value: Set<String>?): SharedPreferences.Editor {
                                editor.putStringSet(key, value); return this
                            }
                            // SharedPreferences publishes memory before discovering a disk error.
                            override fun commit(): Boolean { editor.apply(); return false }
                        }
                    }
                }
            }
        }
        assertEquals(WebhookSettings.WriteResult.STORAGE_FAILED, WebhookSettings.setDeliveryResult(failing,
            WebhookFormat.JSON, setOf(WebhookEventKind.RECOVERY)))
        assertTrue(WebhookSettings.configuration(ctx).generation > before.generation)
        completions.single()(204)
        assertNull(coordinator.health().lastSuccessElapsed)
        assertEquals(WebhookFormat.JSON, WebhookSettings.configuration(ctx).format)
    }

    @Test fun originalSingleWebhookPreferencesRemainTheLegacyReceiver() {
        assertTrue(store().edit().putString("url", secret).putBoolean("enabled", true)
            .putString("format", WebhookFormat.JSON.name)
            .putStringSet("events", setOf(WebhookEventKind.RECOVERY.name))
            .putStringSet("services", setOf("matrix")).commit())

        val legacy = WebhookSettings.configuration(ctx)

        assertEquals(0, legacy.receiverId)
        assertEquals(secret, legacy.url)
        assertTrue(legacy.enabled)
        assertEquals(WebhookFormat.JSON, legacy.format)
        assertEquals(setOf(WebhookEventKind.RECOVERY), legacy.events)
        assertEquals(setOf("matrix"), legacy.services)
        assertEquals(listOf(0), WebhookSettings.receiverIds(ctx))
    }

    @Test fun recipientsKeepPrivateSettingsAndGenerationsIndependentAndOutOfPortableData() {
        val primary = "https://ntfy.sh/private-topic-legacy-51e"
        val additional = "https://ntfy.sh/private-topic-extra-74a"
        assertTrue(WebhookSettings.setUrl(ctx, primary))
        assertTrue(WebhookSettings.setEnabled(ctx, true))
        val primaryBefore = WebhookSettings.configuration(ctx)
        val receiverId = checkNotNull(WebhookSettings.addReceiver(ctx))
        try {
            assertEquals(1, receiverId)
            assertEquals(WebhookSettings.WriteResult.SAVED, WebhookSettings.setUrlResult(ctx, receiverId, additional))
            assertEquals(WebhookSettings.WriteResult.SAVED, WebhookSettings.setEnabledResult(ctx, receiverId, true))
            assertEquals(WebhookSettings.WriteResult.SAVED, WebhookSettings.setDeliveryResult(ctx, receiverId,
                WebhookFormat.JSON_V2, setOf(WebhookEventKind.UNREACHABLE)))
            assertEquals(WebhookSettings.WriteResult.SAVED,
                WebhookSettings.setTargetsResult(ctx, receiverId, setOf("matrix")))

            val configuredPrimary = WebhookSettings.configuration(ctx)
            val configuredAdditional = WebhookSettings.configuration(ctx, receiverId)
            assertEquals(primaryBefore, configuredPrimary)
            assertTrue(configuredAdditional.generation > 0L)
            assertEquals(additional, configuredAdditional.url)
            assertEquals(setOf("matrix"), configuredAdditional.services)
            assertEquals(setOf(WebhookEventKind.UNREACHABLE), configuredAdditional.events)
            assertEquals(WebhookFormat.JSON_V2, configuredAdditional.format)

            val portable = BackupCodec.encode(BackupManager.snapshot(ctx)).toString(Charsets.UTF_8)
            val diagnostics = LocalDiagnostics.snapshot(ctx).toJson()
            val backupSettings = BackupManager.snapshot(ctx).settings.values.joinToString()
            listOf(primary, additional, "private-topic-legacy", "private-topic-extra").forEach { secret ->
                assertFalse(portable.contains(secret))
                assertFalse(diagnostics.contains(secret))
                assertFalse(backupSettings.contains(secret))
            }
        } finally {
            assertTrue(WebhookSettings.removeReceiver(ctx, receiverId))
        }
    }

    @Test fun receiverControlsShowIndependentRecipientWithoutRenderingItsSavedSecret() {
        val receiverId = checkNotNull(WebhookSettings.addReceiver(ctx))
        try {
            assertTrue(WebhookSettings.setUrl(ctx, receiverId, secret))
            ActivityScenario.launch(AutomationSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val receiver = activity.findViewById<Button>(AutomationSettingsActivity.ID_WEBHOOK_RECEIVER)
                    assertEquals(activity.getString(R.string.webhook_receiver_current, 1, 2), receiver.text.toString())
                    assertFalse(activity.findViewById<TextView>(AutomationSettingsActivity.ID_WEBHOOK_URL)
                        .text.contains(secret))
                    assertFalse(activity.findViewById<Button>(AutomationSettingsActivity.ID_WEBHOOK_REMOVE_RECEIVER).isEnabled)
                    assertTrue(activity.findViewById<Button>(AutomationSettingsActivity.ID_WEBHOOK_ADD_RECEIVER).isEnabled)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(R.string.webhook_receiver_current, 1, 2),
                        activity.findViewById<Button>(AutomationSettingsActivity.ID_WEBHOOK_RECEIVER).text.toString())
                }
            }
        } finally {
            assertTrue(WebhookSettings.removeReceiver(ctx, receiverId))
        }
    }

    @Test fun receiverSlotsAreBoundedAndRemovalClearsThatCredential() {
        val ids = mutableListOf<Int>()
        try {
            repeat(WebhookSettings.MAX_RECEIVERS - 1) {
                ids += checkNotNull(WebhookSettings.addReceiver(ctx))
            }
            assertNull(WebhookSettings.addReceiver(ctx))
            val removed = ids.first()
            assertTrue(WebhookSettings.setUrl(ctx, removed, secret))
            assertTrue(WebhookSettings.removeReceiver(ctx, removed))
            assertNull(WebhookSettings.url(ctx, removed))
            assertFalse(WebhookSettings.receiverIds(ctx).contains(removed))
        } finally {
            ids.forEach { id ->
                if (id in WebhookSettings.receiverIds(ctx)) assertTrue(WebhookSettings.removeReceiver(ctx, id))
            }
        }
    }

}
