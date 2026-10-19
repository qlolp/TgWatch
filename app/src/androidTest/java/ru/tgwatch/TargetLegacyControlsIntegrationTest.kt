package ru.tgwatch

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetLegacyControlsIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var data: TargetBackupSnapshot
    private lateinit var saved: Map<String, *>
    @Before fun prepare() {
        HistoryTestStorage.awaitReady()
        saved = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        data = BackupManager.snapshotTargets(ctx)
    }
    @After fun restore() {
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (::data.isInitialized) BackupManager.restoreTargets(ctx, data)
        if (::saved.isInitialized) {
            val edit = Prefs.sp(ctx).edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            check(edit.commit())
        }
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }

    @Test fun legacyStopLeavesMatrixAndTheCommonServiceRunning() {
        start(telegram = true)
        MonitorService.send(ctx, MonitorService.ACTION_STOP)
        await { !TargetPreferences.storedDefinition(ctx, TargetId.TELEGRAM).enabled }
        assertTrue(TargetPreferences.definition(ctx, TargetId.MATRIX).enabled)
        assertTrue(MonitorService.running)
    }
    @Test fun taggedMatrixStopLeavesTelegramRunning() {
        start(telegram = true)
        ctx.startService(Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)
            .putExtra(TargetAutomation.EXTRA_TARGET_ID, TargetId.MATRIX.wire))
        await { !TargetPreferences.storedDefinition(ctx, TargetId.MATRIX).enabled }
        assertTrue(TargetPreferences.definition(ctx, TargetId.TELEGRAM).enabled)
        assertTrue(MonitorService.running)
    }
    @Test fun disabledTelegramWidgetDoesNotBorrowMatrixRunningStateOrCheckIt() {
        start(telegram = false)
        MonitorService::class.java.getDeclaredField("state").apply { isAccessible = true }.set(null,
            MonitorService.State(MonitorService.Status.OK, System.currentTimeMillis(),
                checkedElapsed = SystemClock.elapsedRealtime()))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bindingPermission = WidgetBindingPermission.acquire(ctx, instrumentation.uiAutomation)
        val host = AppWidgetHost(ctx, 80318)
        try {
            val id = host.allocateAppWidgetId()
            assertTrue(AppWidgetManager.getInstance(ctx).bindAppWidgetIdIfAllowed(id,
                ComponentName(ctx, StatusWidget::class.java)))
            // No per-widget setting: an existing widget must still default to Telegram.
            val snapshot = WidgetSnapshot.load(ctx, id)
            assertEquals(TargetId.TELEGRAM, snapshot.target)
            assertFalse(snapshot.running)
            assertFalse(snapshot.fresh)
            var title = ""
            instrumentation.runOnMainSync {
                val root = WidgetRenderer.render(ctx, snapshot, false).apply(ctx, FrameLayout(ctx))
                title = root.findViewById<TextView>(R.id.widgetTitle).text.toString()
            }
            assertEquals(ctx.getString(R.string.widget_goal_off, TargetNames.label(ctx, TargetId.TELEGRAM)), title)
        } finally {
            try {
                host.deleteHost()
            } finally {
                bindingPermission.close()
            }
        }
        var opened: Intent? = null
        var serviceCommands = 0
        val recording = object : ContextWrapper(ctx) {
            override fun startActivity(intent: Intent) { opened = intent }
            override fun startService(intent: Intent): ComponentName? { serviceCommands++; return null }
        }
        StatusWidget.handleClick(recording)
        assertEquals(0, serviceCommands)
        assertEquals("telegram", opened!!.getStringExtra(TargetAutomation.EXTRA_TARGET_ID))
    }

    private fun start(telegram: Boolean) {
        val edit = Prefs.sp(ctx).edit().putBoolean("enabled", true)
        TargetId.entries.forEach { edit.putBoolean(TargetPreferences.key(it, "enabled"), false) }
        check(edit.putBoolean(TargetPreferences.key(TargetId.TELEGRAM, "enabled"), telegram)
            .putBoolean(TargetPreferences.key(TargetId.MATRIX, "enabled"), true).commit())
        MonitorService.start(ctx)
        await { MonitorService.running }
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue("Legacy action did not preserve the independent goal", condition())
    }
}
