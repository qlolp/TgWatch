package ru.tgwatch

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val keys = listOf("onboarding_seen", "enabled", "power_profile", "interval_sec", "last_checked")
    private var previous: Map<String, Any?> = emptyMap()

    @Before fun freshInstallPreferences() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${ctx.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        // A running monitor writes last_checked, which would make this look like an upgrade.
        MonitorService.stopAndAwait(ctx)
        val all = Prefs.sp(ctx).all
        previous = keys.associateWith { all[it] }
        assertTrue(Prefs.sp(ctx).edit().apply { keys.forEach { remove(it) } }.commit())
    }

    @After fun restorePreferences() {
        val edit = Prefs.sp(ctx).edit()
        previous.forEach { (key, value) ->
            when (value) {
                null -> edit.remove(key)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is String -> edit.putString(key, value)
            }
        }
        assertTrue(edit.commit())
    }

    @Test fun showsOnlyOnFreshInstallAndNotAfterAnUpgrade() {
        assertTrue(Onboarding.shouldShow(ctx))
        assertTrue(Prefs.sp(ctx).edit().putLong("last_checked", 1L).commit())
        assertFalse("An install that already ran checks is an upgrade", Onboarding.shouldShow(ctx))
    }

    @Test fun walkingThroughAllPagesMarksTheGuideSeen() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val step = activity.findViewById<TextView>(R.id.tvOnboardingStep)
                val next = activity.findViewById<Button>(R.id.btnOnboardingNext)
                assertEquals("1 / 3", step.text.toString())
                next.performClick()
                next.performClick()
                assertEquals("3 / 3", step.text.toString())
                next.performClick()
                assertTrue(activity.isFinishing)
            }
        }
        assertFalse(Onboarding.shouldShow(ctx))
    }

    @Test fun skippingWithNotificationsAllowedFinishesAtOnce() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.btnOnboardingSkip).performClick()
                assertTrue(activity.isFinishing)
            }
        }
        assertFalse(Onboarding.shouldShow(ctx))
    }
}
