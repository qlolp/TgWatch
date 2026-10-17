package ru.tgwatch

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import android.content.res.Configuration
import android.graphics.Rect
import java.util.Locale
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
    @Test fun widgetSizesAndLargeFontsKeepContentAndActionsInsideBounds() {
        val id = widget(TargetId.MATRIX)
        for (scale in listOf(1f, 1.15f, 1.3f, 2f)) {
            val display = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply {
                fontScale = scale
                setLocale(Locale("ru"))
            })
            val snapshot = WidgetSnapshot.load(display, id).copy(running = true, locked = false,
                lastSuccessAt = System.currentTimeMillis())
            for ((expanded, widthDp, heightDp) in listOf(Triple(false, 200, 72),
                Triple(true, 210, 120), Triple(true, 210, 128))) {
                var failure: Throwable? = null
                instrumentation.runOnMainSync {
                    try {
                        val density = display.resources.displayMetrics.density
                        val width = (widthDp * density).toInt()
                        val height = (heightDp * density).toInt()
                        val root = WidgetRenderer.render(display, snapshot, expanded).apply(display, FrameLayout(display)) as ViewGroup
                        assertEquals(scale, root.resources.configuration.fontScale, 0.01f)
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, width, height)
                        assertTrue(root.contentDescription.toString().contains(
                            LastSuccessPolicy.label(snapshot.lastSuccessAt, System.currentTimeMillis())))
                        val children = mutableListOf(R.id.widgetTitle, R.id.widgetSubtitle, R.id.widgetLastSuccess)
                        if (expanded) children.addAll(listOf(R.id.widgetCoverage, R.id.widgetGraph, R.id.widgetCheck, R.id.widgetMute))
                        for (childId in children) {
                            val child = root.findViewById<View>(childId)
                            if (child.visibility != View.VISIBLE) continue
                            val bounds = Rect(0, 0, child.width, child.height)
                            root.offsetDescendantRectToMyCoords(child, bounds)
                            assertTrue("Child $childId clipped at font $scale, expanded $expanded: $bounds/$height",
                                bounds.top >= 0 && bounds.bottom <= height && bounds.left >= 0 && bounds.right <= width)
                            if (childId == R.id.widgetCheck || childId == R.id.widgetMute)
                                assertTrue(child.height >= (48 * density).toInt())
                            if (childId == R.id.widgetGraph) assertTrue(
                                "Graph " + child.height + "px, expected " + (12 * density) +
                                    "; font=" + scale + ", root=" + height + "; children=" +
                                    children.joinToString { key -> root.findViewById<View>(key).let { v ->
                                        key.toString() + ":h" + v.height + ":visibility" + v.visibility } },
                                child.height >= (12 * density).toInt())
                        }
                    } catch (error: Throwable) { failure = error }
                }
                failure?.let { throw it }
            }
        }
    }
}
