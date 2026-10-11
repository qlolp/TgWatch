package ru.tgwatch

import android.content.res.Configuration
import android.graphics.Insets
import android.os.Build
import android.os.LocaleList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UiAccessibilityTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun chartDescribesPartialUnknownAndEmptyPeriods() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val english = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply {
                setLocales(LocaleList(Locale.ENGLISH))
            })
            Lang.update(english)
            val chart = ChartView(english)
            chart.setData(listOf(History.Minute(10, ok = 1, fail = 2, offline = 3,
                latencySum = 100, partial = 4, unknown = 5)), 10 * 60_000L)
            val summary = chart.contentDescription.toString()
            assertTrue(summary, summary.contains("4 partially reachable"))
            assertTrue(summary, summary.contains("5 unknown"))
            assertTrue(summary, summary.contains("100 ms"))
            chart.setData(emptyList(), 10 * 60_000L)
            assertTrue(chart.contentDescription.toString(), chart.contentDescription.contains("No checks"))
            assertTrue("Keyboard and accessibility users can open period details", chart.isClickable && chart.isFocusable)
            Lang.update(ctx)
        }
    }

    @Test fun selectedAppLocaleControlsResourcesAndDynamicText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val systemLocale = Locale.getDefault()
            try {
            for ((locale, resource, dynamic) in listOf(
                Triple(Locale.ENGLISH, "Check now", "Balanced"),
                Triple(Locale("ru"), "Проверить сейчас", "Обычный"))) {
                Locale.setDefault(if (locale.language == "ru") Locale.ENGLISH else Locale("ru"))
                val localized = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply {
                    setLocales(LocaleList(locale))
                })
                Lang.update(localized)
                assertEquals(resource, localized.getString(R.string.check_now))
                assertEquals(dynamic, PowerProfile.BALANCED.title)
            }
            } finally { Locale.setDefault(systemLocale); Lang.update(ctx) }
        }
    }

    @Test fun mainUiFollowsSelectedAppLanguageAfterRecreation() {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = ctx.getSystemService(android.app.LocaleManager::class.java)
        val previous = manager.applicationLocales
        val wasEnabled = Prefs.isEnabled(ctx)
        Prefs.setEnabled(ctx, false)
        try {
            for ((locale, title) in listOf("en" to "Monitoring is off", "ru" to "Мониторинг выключен")) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    manager.applicationLocales = LocaleList.forLanguageTags(locale)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertEquals(title, activity.findViewById<TextView>(R.id.tvTitle).text.toString())
                        assertEquals(activity.getString(R.string.check_now), activity.findViewById<TextView>(R.id.btnCheck).text.toString())
                    }
                }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.applicationLocales = previous }
            Prefs.setEnabled(ctx, wasEnabled)
            Lang.update(ctx)
        }
    }

    @Test fun controlsKeepTouchTargetsAndTextAtDoubleFontSize() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val large = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { fontScale = 2f })
            val root = LayoutInflater.from(large).inflate(R.layout.activity_main, null) as ScrollView
            root.findViewById<View>(R.id.advancedSettings).visibility = View.VISIBLE
            root.findViewById<View>(R.id.setupCard).visibility = View.VISIBLE
            val width = (360 * large.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, width, root.measuredHeight)
            fun verify(view: View) {
                if (view is Button || view is android.widget.Switch) {
                    val minimum = (48 * large.resources.displayMetrics.density).toInt()
                    assertTrue("${view.resources.getResourceEntryName(view.id)} touch height ${view.height}", view.height >= minimum)
                    assertTrue("${view.resources.getResourceEntryName(view.id)} touch width ${view.width}", view.width >= minimum)
                    if (view.text.isNotEmpty()) {
                        assertTrue("${view.text} must fit vertically", view.height >= view.layout.height + view.compoundPaddingTop + view.compoundPaddingBottom)
                    }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) verify(view.getChildAt(i))
            }
            verify(root)
        }
    }

    @Test fun repeatedInsetsKeepContentClearOfBarsAndCutout() {
        if (Build.VERSION.SDK_INT < 30) return
        val wasEnabled = Prefs.isEnabled(ctx)
        Prefs.setEnabled(ctx, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    val insets = WindowInsets.Builder()
                        .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 32))
                        .setInsets(WindowInsets.Type.displayCutout(), Insets.of(8, 42, 6, 0)).build()
                    repeat(3) { root.dispatchApplyWindowInsets(insets) }
                    assertEquals(8, root.paddingLeft)
                    assertEquals(42, root.paddingTop)
                    assertEquals(6, root.paddingRight)
                    assertEquals(32, root.paddingBottom)
                }
            }
        } finally { Prefs.setEnabled(ctx, wasEnabled) }
    }

    @Test fun statusAndButtonTextHaveReadableContrast() {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color shr shift) and 255) / 255.0
                return if (value <= .04045) value / 12.92 else Math.pow((value + .055) / 1.055, 2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val theme = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
            val card = LayoutInflater.from(theme).inflate(R.layout.activity_main, null)
            val white = luminance(theme.getColor(R.color.on_status))
            for (id in listOf(R.id.tvSince, R.id.tvReason)) {
                assertEquals("Status text must keep its contrast", 1f, card.findViewById<TextView>(id).alpha, 0f)
            }
            for (background in listOf(R.color.status_ok, R.color.status_fail, R.color.status_offline, R.color.status_idle, R.color.tg_blue)) {
                val shade = luminance(theme.getColor(background))
                val contrast = (maxOf(white, shade) + .05) / (minOf(white, shade) + .05)
                assertTrue("${theme.resources.getResourceEntryName(background)} contrast $contrast", contrast >= 4.5)
            }
        }
    }
}
