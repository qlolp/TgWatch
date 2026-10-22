package ru.tgwatch

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RegionalIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private lateinit var preferences: Map<String, *>

    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        check(!BackupManager.hasPending())
        preferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        val edit = Prefs.sp(ctx).edit().clear().putBoolean("enabled", false)
        TargetId.entries.forEach { edit.putBoolean(TargetPreferences.key(it, "enabled"), false) }
        check(edit.commit())
        RegionalStateStore.clear()
        EgressCountryUi.invalidate(ctx)
    }

    @After fun restore() {
        if (!::preferences.isInitialized) return
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        val edit = Prefs.sp(ctx).edit().clear()
        preferences.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
        } }
        check(edit.commit())
        RegionalStateStore.clear()
    }

    @Test fun regionalOnlyServiceSurvivesTelegramStopWithoutEnablingHistoricalTargets() {
        assertTrue(RegionalPreferences.setEnabled(ctx, RegionalService.YOUTUBE, true))
        Prefs.setEnabled(ctx, true)
        instrumentation.runOnMainSync { MonitorService.start(ctx) }
        awaitRunning()
        instrumentation.runOnMainSync { MonitorService.send(ctx, MonitorService.ACTION_STOP) }
        instrumentation.waitForIdleSync()
        assertTrue(Prefs.isEnabled(ctx))
        assertTrue(MonitorService.running)
        assertTrue(TargetId.entries.none { TargetPreferences.definition(ctx, it).enabled })
        assertTrue(RegionalPreferences.enabled(ctx, RegionalService.YOUTUBE))
        assertTrue(MonitorService.requestRegionalCheck())
    }

    @Test fun quietHoursAndRegionalMaintenanceSuppressDeliveryWithoutConsumingEvidence() {
        val result = RegionalResult(RegionalService.GEMINI, RegionalStatus.REGION_RESTRICTED,
            System.currentTimeMillis(), SystemClock.elapsedRealtime(), 1, 1, 1)
        val notifications = RegionalNotifications(ctx)
        val calendar = java.util.Calendar.getInstance()
        val minute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        Prefs.setQuietHoursMinutes(ctx, (minute + 1439) % 1440, (minute + 2) % 1440)
        Prefs.sp(ctx).edit().putBoolean("quiet_hours", true).commit()
        assertFalse(notifications.post(result))
        Prefs.sp(ctx).edit().putBoolean("quiet_hours", false).commit()
        MaintenanceWindows.save(ctx, RegionalPreferences.MAINTENANCE,
            listOf(MaintenanceWindow("test", 0, 1440)))
        assertFalse(notifications.post(result))
        assertFalse(Prefs.sp(ctx).contains("regional.gemini.delivered_at"))
        notifications.clearAll()
    }

    @Test fun countryCannotBecomeCurrentAfterBootIdentityChanges() {
        val snapshot = EgressCountry.Snapshot("DE", System.currentTimeMillis(), 42)
        Prefs.setEgressCountry(ctx, snapshot)
        assertEquals(snapshot, Prefs.egressCountry(ctx))
        val boot = android.provider.Settings.Global.getInt(ctx.contentResolver,
            android.provider.Settings.Global.BOOT_COUNT, -1)
        Prefs.sp(ctx).edit().putInt("egress_boot", boot + 1).commit()
        assertNull(Prefs.egressCountry(ctx))
    }

    @Test fun changingAnotherProfileCannotReopenAnAlreadyWarnedIncident() {
        assertTrue(RegionalPreferences.setEnabled(ctx, RegionalService.GEMINI, true))
        RegionalPreferences.delivered(ctx, RegionalService.GEMINI, 42)
        Prefs.sp(ctx).edit().putLong("regional.gemini.delivered_at",
            SystemClock.elapsedRealtime() - RegionalConsensus.COOLDOWN_MS).commit()
        assertTrue(RegionalPreferences.setEnabled(ctx, RegionalService.YOUTUBE, true))
        assertFalse(RegionalPreferences.notificationAllowed(ctx, RegionalService.GEMINI, 42))
        RegionalPreferences.reachable(ctx, RegionalService.GEMINI)
        assertTrue(RegionalPreferences.notificationAllowed(ctx, RegionalService.GEMINI, 42))
    }

    @Test fun aLateResponseCannotPublishAfterDisableEnableOrNetworkInvalidation() {
        assertNotNull(ctx.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
        assertTrue(RegionalPreferences.setEnabled(ctx, RegionalService.YOUTUBE, true))
        Prefs.setEnabled(ctx, true)
        for (networkChange in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val published = CountDownLatch(1)
            val monitor = RegionalMonitor(ctx, { true }, { published.countDown() },
                { service, network, _, revision, configuration ->
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    finished.countDown()
                    RegionalResult(service, RegionalStatus.REGION_RESTRICTED, System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(), network.networkHandle, revision, configuration)
                })
            try {
                monitor.tick()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (networkChange) monitor.changed() else {
                    assertTrue(RegionalPreferences.setEnabled(ctx, RegionalService.YOUTUBE, false))
                    assertTrue(RegionalPreferences.setEnabled(ctx, RegionalService.YOUTUBE, true))
                }
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                assertFalse(published.await(1, TimeUnit.SECONDS))
                assertNull(RegionalStateStore.get(RegionalService.YOUTUBE))
            } finally { release.countDown(); monitor.close() }
        }
    }

    @Test fun regionalControlsRemainReadableAtDoubleFontScale() {
        val previous = Configuration(ctx.resources.configuration)
        val config = Configuration(previous).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        ctx.resources.updateConfiguration(config, ctx.resources.displayMetrics)
        try {
            ActivityScenario.launch<RegionalServicesActivity>(Intent(ctx, RegionalServicesActivity::class.java)).use {
                it.onActivity { activity ->
                    fun controls(view: View): List<View> = listOf(view) +
                        if (view is ViewGroup) (0 until view.childCount).flatMap { index ->
                            controls(view.getChildAt(index))
                        } else emptyList()
                    val controls = controls(activity.window.decorView).filter { view ->
                        view is Button || view is Switch
                    }
                    assertTrue(controls.size >= 10)
                    controls.forEach { view ->
                        assertTrue("48dp touch target", view.height >=
                            (48 * view.resources.displayMetrics.density).toInt())
                    }
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            ctx.resources.updateConfiguration(previous, ctx.resources.displayMetrics)
        }
    }

    private fun awaitRunning() {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!MonitorService.running && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(MonitorService.running)
    }
}
