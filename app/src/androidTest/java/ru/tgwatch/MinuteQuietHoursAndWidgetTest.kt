package ru.tgwatch

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinuteQuietHoursAndWidgetTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = listOf("enabled", "quiet_hours", "quiet_start_hour", "quiet_end_hour",
        "quiet_start_minute", "quiet_end_minute", "last_success_at")

    private fun withSavedSettings(block: () -> Unit) {
        val settings = Prefs.sp(ctx)
        val previous = keys.associateWith { settings.all[it] }
        settings.edit().apply { keys.forEach { remove(it) } }.commit()
        try {
            block()
        } finally {
            settings.edit().apply {
                previous.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is String -> putString(key, value)
                    else -> remove(key)
                } }
            }.commit()
        }
    }

    private fun withMeasuredWidget(assertions: (ViewGroup, TextView) -> Unit) {
        val method = StatusWidget.Companion::class.java.getDeclaredMethod("build", Context::class.java)
        method.isAccessible = true
        val remote = method.invoke(StatusWidget.Companion, ctx) as RemoteViews
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = remote.apply(ctx, FrameLayout(ctx)) as ViewGroup
            val density = ctx.resources.displayMetrics.density
            val width = (200 * density).roundToInt()
            val height = (72 * density).roundToInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertions(root, root.findViewById(R.id.widgetLastSuccess))
        }
    }

    @Test fun defaultsAndLegacyHoursMigrateWithoutChangingLegacyKeys() = withSavedSettings {
        assertEquals(23 * 60, Prefs.quietStartMinute(ctx))
        assertEquals(8 * 60, Prefs.quietEndMinute(ctx))
        Prefs.sp(ctx).edit().putInt("quiet_start_hour", 22).putInt("quiet_end_hour", 7).commit()
        assertEquals(22 * 60, Prefs.quietStartMinute(ctx))
        assertEquals(7 * 60, Prefs.quietEndMinute(ctx))
        Prefs.setQuietHoursMinutes(ctx, 1395, 525)
        assertEquals(1395, Prefs.quietStartMinute(ctx))
        assertEquals(525, Prefs.quietEndMinute(ctx))
        assertEquals(23, Prefs.quietStartHour(ctx))
        assertEquals(8, Prefs.quietEndHour(ctx))
        assertEquals(23, Prefs.sp(ctx).getInt("quiet_start_hour", -1))
        assertEquals(8, Prefs.sp(ctx).getInt("quiet_end_hour", -1))
        // An old caller changing hours must not leave stale minute overrides.
        Prefs.setQuietHours(ctx, 22, 7)
        assertEquals(1320, Prefs.quietStartMinute(ctx))
        assertEquals(420, Prefs.quietEndMinute(ctx))
    }

    @Test fun localMinuteBoundariesControlRealAlertPreference() = withSavedSettings {
        Prefs.setQuietHoursMinutes(ctx, 1395, 525)
        Prefs.setQuietHoursEnabled(ctx, true)
        fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 10, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertFalse(Prefs.inQuietHoursNow(ctx, at(23, 14)))
        assertTrue(Prefs.inQuietHoursNow(ctx, at(23, 15)))
        assertTrue(Prefs.inQuietHoursNow(ctx, at(8, 44)))
        assertFalse(Prefs.inQuietHoursNow(ctx, at(8, 45)))
        Prefs.setQuietHoursMinutes(ctx, 525, 525)
        assertFalse(Prefs.inQuietHoursNow(ctx, at(8, 45)))
        Prefs.setQuietHoursEnabled(ctx, false)
        assertFalse(Prefs.inQuietHoursNow(ctx, at(23, 15)))
    }

    @Test fun activityMinuteLabelSurvivesRecreation() = withSavedSettings {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        }
        Prefs.sp(ctx).edit().putBoolean("enabled", false).commit()
        Prefs.setQuietHoursMinutes(ctx, 1395, 525)
        Prefs.setQuietHoursEnabled(ctx, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun checkLabel(activity: MainActivity) {
                val quiet = activity.findViewById<Switch>(R.id.swQuietHours)
                assertTrue(quiet.isChecked)
                assertTrue(quiet.text.toString().contains("23:15–08:45"))
            }
            scenario.onActivity { checkLabel(it) }
            scenario.recreate()
            scenario.onActivity { checkLabel(it) }
        }
    }

    @Test fun persistedOkSurvivesPartialFailureAndStoreReopening() = withSavedSettings {
        LastSuccessStore.record(ctx, "OK", 1000)
        for (status in listOf("PARTIAL", "TG_DOWN", "NO_NETWORK", "UNKNOWN")) {
            LastSuccessStore.record(ctx, status, 2000)
            assertEquals(1000L, LastSuccessStore.lastSuccessAt(ctx))
        }
        val reopened = ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences("tgwatch", Context.MODE_PRIVATE)
        assertEquals(1000L, reopened.getLong("last_success_at", 0))
        LastSuccessStore.record(ctx, "OK", 500)
        assertEquals(500L, LastSuccessStore.lastSuccessAt(ctx))
        LastSuccessStore.record(ctx, "OK", 0)
        assertEquals(500L, LastSuccessStore.lastSuccessAt(ctx))
        LastSuccessStore.clear(ctx)
        assertEquals(0L, LastSuccessStore.lastSuccessAt(ctx))
    }

    @Test fun actualWidgetViewsShowPersistedOkAndClockRollbackMarker() = withSavedSettings {
        val now = System.currentTimeMillis()
        LastSuccessStore.record(ctx, "OK", now - 60_000)
        LastSuccessStore.record(ctx, "PARTIAL", now)
        fun lastSuccessText(): String {
            var text = ""
            withMeasuredWidget { root, lastSuccess ->
                text = lastSuccess.text.toString()
                val bounds = Rect()
                lastSuccess.getDrawingRect(bounds)
                root.offsetDescendantRectToMyCoords(lastSuccess, bounds)
                assertEquals(View.VISIBLE, lastSuccess.visibility)
                assertTrue("Last success must have visible space", lastSuccess.height > 0)
                assertTrue("Last success must fit inside the widget", bounds.bottom <= root.height)
            }
            return text
        }
        assertEquals(LastSuccessPolicy.label(now - 60_000, now), lastSuccessText())
        LastSuccessStore.record(ctx, "OK", now + 86_400_000)
        assertTrue(lastSuccessText().contains("Часы изменились · OK"))
        LastSuccessStore.clear(ctx)
        assertEquals("Последний OK: —", lastSuccessText())
    }

    @Test fun narrowWidgetShowsCompleteDateTimeAndClockMarkerWithoutClipping() = withSavedSettings {
        val now = System.currentTimeMillis()
        for (checkedAt in listOf(now - 60_000, now + 86_400_000)) {
            LastSuccessStore.record(ctx, "OK", checkedAt)
            withMeasuredWidget { root, lastSuccess ->
                val timestamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(checkedAt))
                val text = lastSuccess.text.toString()
                assertTrue("Full date and time must be present", text.contains(timestamp))
                assertTrue(text.contains(if (checkedAt > now) "Часы изменились · OK" else "Последний OK:"))
                val layout = lastSuccess.layout
                    ?: throw AssertionError("Text must be laid out at exactly 200dp × 72dp")
                assertTrue(layout.lineCount in 1..2)
                val lastLine = layout.lineCount - 1
                assertEquals("The final date/time characters must not be ellipsized", 0,
                    layout.getEllipsisCount(lastLine))
                assertEquals("Every date/time and marker character must reach the layout",
                    text.length, layout.getLineEnd(lastLine))
                for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                val bounds = Rect()
                lastSuccess.getDrawingRect(bounds)
                root.offsetDescendantRectToMyCoords(lastSuccess, bounds)
                assertTrue("Last-success row must be below the top edge", bounds.top >= 0)
                assertTrue("Last-success row must remain inside the measured widget", bounds.bottom <= root.height)
                assertTrue(lastSuccess.height >= layout.height)
            }
        }
    }
}
