package ru.tgwatch

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MultiWidgetIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val manager get() = AppWidgetManager.getInstance(ctx)
    private lateinit var host: AppWidgetHost
    private lateinit var saved: Map<String, *>
    private lateinit var history: TargetBackupSnapshot
    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        saved = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        history = BackupManager.snapshotTargets(ctx)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        host = AppWidgetHost(ctx, 80317)
    }
    @After fun restore() {
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (::host.isInitialized) host.deleteHost()
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        if (::history.isInitialized) BackupManager.restoreTargets(ctx, history)
        if (::saved.isInitialized) {
            val edit = Prefs.sp(ctx).edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
            } }
            check(edit.commit())
        }
        if (!Prefs.isEnabled(ctx)) WatchdogReceiver.cancel(ctx)
    }
    private fun widget(target: TargetId): Int {
        val id = host.allocateAppWidgetId()
        assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(ctx, StatusWidget::class.java)))
        assertTrue(WidgetPreferences.setTarget(ctx, id, target))
        return id
    }
    @Test fun identitiesAndReusedConfigurationAreIndependentWithoutStartingMonitoring() {
        val first = widget(TargetId.MATRIX)
        val second = widget(TargetId.WHATSAPP)
        val a = WidgetIdentity(first, TargetId.MATRIX, "check", WidgetPreferences.token(ctx, first)!!)
        val b = WidgetIdentity(second, TargetId.WHATSAPP, "check", WidgetPreferences.token(ctx, second)!!)
        assertNotEquals(StatusWidget.pending(ctx, a), StatusWidget.pending(ctx, b))
        assertNotEquals(StatusWidget.pending(ctx, a), StatusWidget.pending(ctx, a.copy(action = "mute")))
        val old = StatusWidget.pending(ctx, a)
        assertTrue(WidgetPreferences.remove(ctx, first))
        assertTrue(WidgetPreferences.setTarget(ctx, first, TargetId.MATRIX))
        assertFalse(WidgetPreferences.current(ctx, a))
        assertNotEquals(old, StatusWidget.pending(ctx, a.copy(token = WidgetPreferences.token(ctx, first)!!)))
        old.send()
        SystemClock.sleep(300)
        assertFalse(MonitorService.running)
        assertEquals(TargetId.WHATSAPP, WidgetPreferences.target(ctx, second))
    }
    @Test fun renderDoesNotWaitForHistoryAndUsesOnlyTheChosenGoal() {
        val id = widget(TargetId.MATRIX)
        WidgetHistoryCache.prepare(ctx, TargetId.MATRIX)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        var text = ""
        val holder = Thread { synchronized(History) { entered.countDown(); release.await() } }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    val snapshot = WidgetSnapshot.load(ctx, id)
                    val root = WidgetRenderer.render(ctx, snapshot, true).apply(ctx, FrameLayout(ctx))
                    text = root.findViewById<TextView>(R.id.widgetTitle).text.toString()
                } finally { rendered.countDown() }
            }
            assertTrue("Rendering must not acquire the history monitor", rendered.await(5, TimeUnit.SECONDS))
            assertTrue(text.contains("Matrix"))
        } finally { release.countDown(); holder.join(5000) }
    }
    @Test fun matrixWidgetMuteIsDurableAndDoesNotMuteTelegram() {
        val id = widget(TargetId.MATRIX)
        val edit = Prefs.sp(ctx).edit().putBoolean("enabled", true)
        TargetId.entries.forEach { edit.putBoolean(TargetPreferences.key(it, "enabled"), it == TargetId.MATRIX) }
        check(edit.commit())
        TargetAlertPreferences.clear(ctx, TargetId.MATRIX)
        TargetAlertPreferences.clear(ctx, TargetId.TELEGRAM)
        MonitorService.start(ctx)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!MonitorService.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(MonitorService.running)
        val identity = WidgetIdentity(id, TargetId.MATRIX, "mute", WidgetPreferences.token(ctx, id)!!)
        StatusWidget.pending(ctx, identity).send()
        while (TargetAlertPreferences.remaining(ctx, TargetId.MATRIX) == 0L &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(TargetAlertPreferences.remaining(ctx, TargetId.MATRIX) > 0L)
        assertEquals(0L, TargetAlertPreferences.remaining(ctx, TargetId.TELEGRAM))
    }
}
