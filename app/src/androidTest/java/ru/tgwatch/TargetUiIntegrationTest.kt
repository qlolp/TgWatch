package ru.tgwatch

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetUiIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private lateinit var data: TargetBackupSnapshot
    private lateinit var preferences: Map<String, *>
    private lateinit var privatePreferences: Map<String, *>
    private val privateStore get() = ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)

    @Before fun prepare() {
        HistoryTestStorage.awaitReady()
        preferences = Prefs.sp(ctx).all.toMap()
        privatePreferences = privateStore.all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        data = BackupManager.snapshotTargets(ctx)
        Onboarding.markSeen(ctx)
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun restore() {
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (::data.isInitialized) BackupManager.restoreTargets(ctx, data)
        if (::privatePreferences.isInitialized) restore(privateStore, privatePreferences)
        if (::preferences.isInitialized) restore(Prefs.sp(ctx), preferences)
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }

    @Test fun switchingTargetKeepsSeparateHistoryAndRotationKeepsSelection() {
        val now = System.currentTimeMillis()
        HistorySessions.queue(ctx).exclusive {
            History.replaceSnapshot(ctx, listOf(Observation(now - 10_000, now, "OK", 20)))
            TargetPersistence.storage().replaceRows(TargetId.MATRIX,
                listOf(TargetObservation(Observation(now - 10_000, now, "DOWN"),
                    TargetPreferences.endpointRevision(ctx, TargetId.MATRIX))))
        }.get()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText(scenario, R.id.tvUptime, formatPercent(ctx, 100.0))
            select(scenario, TargetId.MATRIX)
            awaitText(scenario, R.id.tvUptime, formatPercent(ctx, 0.0))
            scenario.onActivity {
                assertTrue(it.findViewById<TextView>(R.id.tvDayStats).text.contains("Matrix"))
            }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(TargetId.MATRIX.ordinal, it.findViewById<Spinner>(R.id.targetSelector).selectedItemPosition)
            }
            awaitText(scenario, R.id.tvUptime, formatPercent(ctx, 0.0))
            select(scenario, TargetId.TELEGRAM)
            awaitText(scenario, R.id.tvUptime, formatPercent(ctx, 100.0))
        }
    }

    @Test fun overviewListsIndependentTargetsWithoutAddressesAndSelectsOne() {
        val secret = "https://private.example/overview-must-not-show-this"
        check(privateStore.edit().putString(TargetId.CUSTOM.wire, secret).commit())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.btnTargetOverview).performClick()
                val selection = MainActivity::class.java.getDeclaredField("targetUi").apply { isAccessible = true }
                    .get(activity)
                val overview = selection.javaClass.getDeclaredField("overview").apply { isAccessible = true }
                    .get(selection)
                val dialog = overview.javaClass.getDeclaredField("dialog").apply { isAccessible = true }
                    .get(overview) as AlertDialog
                assertTrue(dialog.isShowing)
                val buttons = findButtons(dialog.window!!.decorView)
                for (id in TargetSlots.visible(activity)) {
                    val label = TargetNames.label(activity, id)
                    assertTrue("Missing independent overview row for $label",
                        buttons.any { it.text.toString().startsWith(label + "\n") })
                }
                assertFalse(buttons.joinToString("\n") { it.text.toString() }.contains(secret))
                val matrix = buttons.single { it.text.toString().startsWith("Matrix\n") }
                matrix.performClick()
                assertFalse(dialog.isShowing)
                assertEquals(TargetId.MATRIX.ordinal,
                    activity.findViewById<Spinner>(R.id.targetSelector).selectedItemPosition)
            }
        }
    }

    @Test fun privateEndpointIsNotPrefilledSavedOrExportedFromTheSettingsDialog() {
        val secret = "https://private.example/never-export-this-path"
        check(privateStore.edit().putString(TargetId.CUSTOM.wire, secret).commit())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, TargetId.CUSTOM)
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.btnTargetSettings).performClick()
                val helper = MainActivity::class.java.getDeclaredField("targetUi").apply { isAccessible = true }.get(activity)
                val dialog = helper.javaClass.getDeclaredField("dialog").apply { isAccessible = true }.get(helper) as AlertDialog
                assertTrue(dialog.isShowing)
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val input = dialog.findViewById<EditText>(R.id.targetEndpointInput)
                assertTrue("A saved private address must never be prefilled", input.text.isEmpty())
                assertFalse(input.isSaveEnabled)
                assertFalse(input.isSaveFromParentEnabled)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.importantForAutofill)
                input.setText(secret)
                val state = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, state)
                val parcel = Parcel.obtain()
                val bytes = try { parcel.writeBundle(state); parcel.marshall() } finally { parcel.recycle() }
                assertFalse(String(bytes, Charsets.UTF_8).contains(secret))
                assertFalse(String(bytes, Charsets.UTF_16LE).contains(secret))
                dialog.dismiss()
            }
        }
        val bytes = TargetBackupCodec.encode(BackupManager.snapshotTargets(ctx))
        try { assertFalse(bytes.toString(Charsets.UTF_8).contains(secret)) } finally { bytes.fill(0) }
    }

    @Test fun oneTargetMuteDoesNotMuteAnother() {
        assertTrue(TargetAlertPreferences.mute(ctx, TargetId.MATRIX))
        assertTrue(TargetAlertPreferences.remaining(ctx, TargetId.MATRIX) > 0L)
        assertEquals(0, TargetAlertPreferences.remaining(ctx, TargetId.TELEGRAM))
        assertTrue(TargetAlertPreferences.clear(ctx, TargetId.MATRIX))
        assertEquals(0, TargetAlertPreferences.remaining(ctx, TargetId.MATRIX))
    }

    @Test fun savingTelegramIntervalAlsoUpdatesItsExistingControls() {
        Prefs.setProfile(ctx, PowerProfile.BALANCED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.btnTargetSettings).performClick()
                val helper = MainActivity::class.java.getDeclaredField("targetUi").apply { isAccessible = true }.get(activity)
                val dialog = helper.javaClass.getDeclaredField("dialog").apply { isAccessible = true }.get(helper) as AlertDialog
                val interval = findSpinner(dialog.window!!.decorView)!!
                val wanted = activity.getString(R.string.target_interval, 60)
                val index = (0 until interval.adapter.count).first { interval.adapter.getItem(it).toString() == wanted }
                interval.setSelection(index)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            val end = SystemClock.elapsedRealtime() + 5_000
            val matched = AtomicBoolean()
            do {
                scenario.onActivity { matched.set(it.findViewById<RadioGroup>(R.id.rgInterval).checkedRadioButtonId == R.id.rb60) }
                if (!matched.get()) SystemClock.sleep(50)
            } while (!matched.get() && SystemClock.elapsedRealtime() < end)
            assertEquals(60, Prefs.intervalSec(ctx))
            assertTrue("Legacy controls must reflect the applied target interval", matched.get())
        }
    }

    private fun findSpinner(view: View): Spinner? = if (view is Spinner) view else if (view is ViewGroup)
        (0 until view.childCount).asSequence().mapNotNull { findSpinner(view.getChildAt(it)) }.firstOrNull() else null

    private fun findButtons(view: View): List<Button> = when {
        view is Button -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { findButtons(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: TargetId) {
        scenario.onActivity { it.findViewById<Spinner>(R.id.targetSelector).setSelection(id.ordinal) }
    }
    private fun awaitText(scenario: ActivityScenario<MainActivity>, id: Int, expected: String) {
        val matched = AtomicBoolean()
        val end = SystemClock.elapsedRealtime() + 10_000
        do {
            scenario.onActivity { matched.set(it.findViewById<TextView>(id).text.toString() == expected) }
            if (!matched.get()) SystemClock.sleep(50)
        } while (!matched.get() && SystemClock.elapsedRealtime() < end)
        assertTrue("Expected selected target value $expected", matched.get())
    }
    private fun restore(store: SharedPreferences, values: Map<String, *>) {
        val edit = store.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(edit.commit())
    }
}
