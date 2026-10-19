package ru.tgwatch

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val keys = listOf("onboarding_seen", "enabled", "power_profile", "interval_sec", "last_checked", "notification_permission_asked", "log")
    private var previous: Map<String, Any?> = emptyMap()

    @Before fun freshInstallPreferences() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            testName.methodName != "permanentlyDeniedNotificationActionReturnsFromSettings") {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${ctx.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        // A running monitor writes last_checked, which would make this look like an upgrade.
        MonitorService.stopAndAwait(ctx)
        val all = Prefs.sp(ctx).all
        previous = keys.associateWith { all[it] }
        assertTrue(Prefs.sp(ctx).edit().apply { keys.forEach { remove(it) } }.commit())
        EventLog.invalidate()
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
        EventLog.invalidate()
    }

    @Test fun legacyStartupLogWithoutCompletedCheckIsAnUpgrade() {
        assertTrue(Onboarding.shouldShow(ctx))
        Prefs.sp(ctx).edit().putString("log", "legacy monitoring started").commit()
        assertFalse("A previously running legacy install must keep its monitoring preference",
            Onboarding.shouldShow(ctx))
    }

    @Test fun guideReopensAfterAllPermissionsGranted() {
        Prefs.setEnabled(ctx, false)
        Onboarding.markSeen(ctx)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainSetupUi.render(activity, true, true, true)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.setupCard).visibility)
                assertTrue("The guide must remain available for configured users",
                    activity.findViewById<Button>(R.id.btnOnboarding).isShown)
            }
        }
    }

    @Test fun guideReflectsGrantedNotifications() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.btnOnboardingNext).performClick()
                assertFalse("A granted permission must not offer a dead request button",
                    activity.findViewById<Button>(R.id.btnOnboardingAction).isEnabled)
            }
        }
    }

    @Test fun leavingFreshInstallGuideDoesNotEnableMonitoring() {
        val history = History.snapshot(ctx)
        val log = EventLog.all(ctx).toList()
        val monitor = instrumentation.addMonitor(OnboardingActivity::class.java.name, null, false)
        var main: android.app.Activity? = null
        var guide: android.app.Activity? = null
        try {
            main = instrumentation.startActivitySync(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            guide = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
            assertTrue("A fresh install must open the guide", guide != null)
            assertFalse("Opening the guide must not enable monitoring", Prefs.isEnabled(ctx))
            assertFalse(MonitorService.running)
            instrumentation.runOnMainSync { guide?.findViewById<Button>(R.id.btnOnboardingSkip)?.performClick() }
            assertFalse(Prefs.isEnabled(ctx))
        } finally {
            instrumentation.runOnMainSync { guide?.finish(); main?.finish() }
            instrumentation.removeMonitor(monitor)
            Prefs.setEnabled(ctx, false)
            MonitorService.stopAndAwait(ctx)
            History.replaceSnapshot(ctx, history)
            EventLog.replace(ctx, log)
        }
    }

    @Test fun guideReflectsDeniedAndGrantedPermissions() {
        val power = ctx.getSystemService(android.os.PowerManager::class.java)
        val previouslyAllowed = power.isIgnoringBatteryOptimizations(ctx.packageName)
        try {
            shell("dumpsys deviceidle whitelist -${ctx.packageName}")
            ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    repeat(2) { activity.findViewById<Button>(R.id.btnOnboardingNext).performClick() }
                    assertTrue("Unrestricted background use has not been granted",
                        activity.findViewById<Button>(R.id.btnOnboardingAction).isEnabled)
                }
                shell("dumpsys deviceidle whitelist +${ctx.packageName}")
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    assertFalse("Returning from settings must refresh permission actions",
                        activity.findViewById<Button>(R.id.btnOnboardingAction).isEnabled)
                }
            }
        } finally { shell("dumpsys deviceidle whitelist ${if (previouslyAllowed) "+" else "-"}${ctx.packageName}") }
    }

    // Run in a fresh instrumentation after the host revokes POST_NOTIFICATIONS and sets user-fixed.
    @Test fun permanentlyDeniedNotificationActionReturnsFromSettings() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue("Host must revoke notifications before instrumentation",
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        Prefs.sp(ctx).edit().putBoolean("notification_permission_asked", true).commit()
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.btnOnboardingNext).performClick()
                assertFalse(activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                val action = activity.findViewById<Button>(R.id.btnOnboardingAction)
                assertTrue(action.isEnabled)
                val monitor = instrumentation.addMonitor(
                    android.content.IntentFilter(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS),
                    android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
                try { action.performClick(); assertEquals(1, monitor.hits) }
                finally { instrumentation.removeMonitor(monitor) }
            }
            instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<Button>(R.id.btnOnboardingAction).isEnabled)
            }
        }
    }

    @Test fun rotationKeepsPageAtFontScaleTwo() {
        val previous = shell("settings get system font_scale").trim().toFloatOrNull()
        try {
            shell("settings put system font_scale 2.0")
            ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 5_000
                var scale = 1f
                while (scale < 1.9f && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { scale = it.resources.configuration.fontScale }
                    if (scale < 1.9f) SystemClock.sleep(100)
                }
                assertEquals(2f, scale, 0.01f)
                scenario.onActivity { it.findViewById<Button>(R.id.btnOnboardingNext).performClick() }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("2 / 3", activity.findViewById<TextView>(R.id.tvOnboardingStep).text.toString())
                    assertTrue(activity.findViewById<Button>(R.id.btnOnboardingNext).isShown)
                }
            }
        } finally {
            shell(if (previous == null) "settings delete system font_scale" else "settings put system font_scale $previous")
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes(), Charsets.UTF_8) }

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
