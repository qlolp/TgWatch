package ru.tgwatch

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import java.util.concurrent.ExecutorService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android publication boundary without initiating network probes. */
@RunWith(AndroidJUnit4::class)
class ExtraPublicationIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val type = MonitorService::class.java
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private val state = MonitorService.State(status = MonitorService.Status.PARTIAL, checkedAt = 123_000,
        checkedElapsed = 456_000, since = 120_000, latencyMs = 42, reason = "primary reason",
        lastVibrationAt = 110_000, diagnostics = "primary diagnostics", expectedIntervalSec = 30)

    private fun fixture(block: (MonitorService, ExtraProbeCoordinator, CheckSchedule, Long) -> Unit) {
        val preferences = Prefs.sp(ctx)
        val enabled = Prefs.isEnabled(ctx)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        val originalState = MonitorService.state
        val originalEndpoints = MonitorService.endpointOutcomes()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val selected = cm.activeNetwork
        assumeNotNull("Requires the emulator's active Android Network", selected)
        val service = MonitorService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, ctx)
        field("cm").set(service, cm)
        val schedule = CheckSchedule({}, {})
        field("checkSchedule").set(service, schedule)
        val coordinator = field("extraCoordinator").get(service) as ExtraProbeCoordinator
        assertTrue(preferences.edit().putBoolean("enabled", true).commit())
        field("activeService").set(null, service)
        field("running").setBoolean(null, true)
        field("state").set(null, state)
        field("lastEndpoints").set(null, emptyList<ProbeOutcome>())
        try {
            block(service, coordinator, schedule, selected!!.networkHandle)
        } finally {
            coordinator.close()
            (field("probeExecutor").get(service) as ExecutorService).shutdownNow()
            (field("extraProbeExecutor").get(service) as ExecutorService).shutdownNow()
            field("running").setBoolean(null, false)
            field("activeService").set(null, null)
            field("state").set(null, originalState)
            field("lastEndpoints").set(null, originalEndpoints)
            assertTrue(preferences.edit().putBoolean("enabled", enabled).commit())
        }
    }

    private fun publish(service: MonitorService, token: ExtraProbeCoordinator.Token) {
        val probeType = Class.forName("ru.tgwatch.MonitorService\$Probe")
        val primary = probeType.declaredConstructors.first { it.parameterCount == 6 }
            .apply { isAccessible = true }
            .newInstance(state.status, state.latencyMs, state.reason, state.diagnostics, emptyList<ProbeOutcome>(), null)
        val outcome = ProbeOutcome("fixture", ProbeGroup.EXTRA, true, 12, "fixture response")
        type.getDeclaredMethod("publishExtras", probeType, ExtraProbeCoordinator.Token::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(service, primary, token, listOf(outcome))
    }

    @Test fun matchingExtrasChangeOnlyDiagnosticsAndEndpointListing() = fixture { service, coordinator, schedule, network ->
        val preferences = Prefs.sp(ctx).all.toMap()
        val history = History.snapshot(ctx)
        val log = EventLog.all(ctx).toList()
        val scheduling = MonitorService.schedulingDiagnostics()
        val token = coordinator.begin(schedule.revision, network)
        publish(service, token)
        val updated = MonitorService.state
        assertTrue(updated.diagnostics.contains("fixture response"))
        assertEquals(state, updated.copy(diagnostics = state.diagnostics))
        assertEquals(1, MonitorService.endpointOutcomes().size)
        assertEquals(preferences, Prefs.sp(ctx).all)
        assertEquals(history, History.snapshot(ctx))
        assertEquals(log, EventLog.all(ctx))
        assertEquals(scheduling, MonitorService.schedulingDiagnostics())
        assertEquals(0L, schedule.revision)
    }

    @Test fun staleNetworkRevisionStopRestoreAndInstanceRejectLateExtras() = fixture { service, coordinator, schedule, network ->
        fun reject(token: ExtraProbeCoordinator.Token) {
            publish(service, token)
            assertEquals(state, MonitorService.state)
            assertTrue(MonitorService.endpointOutcomes().isEmpty())
        }
        val stale = coordinator.begin(schedule.revision, network)
        coordinator.invalidate()
        reject(stale)
        reject(coordinator.begin(schedule.revision, network + 1))
        val revision = coordinator.begin(schedule.revision, network)
        schedule.networkChanged(0)
        reject(revision)
        val token = coordinator.begin(schedule.revision, network)
        assertTrue(Prefs.sp(ctx).edit().putBoolean("enabled", false).commit())
        reject(token)
        assertTrue(Prefs.sp(ctx).edit().putBoolean("enabled", true).commit())
        field("stopping").setBoolean(service, true)
        reject(token)
        field("stopping").setBoolean(service, false)
        val restoring = BackupManager::class.java.getDeclaredField("replacingHistory").apply { isAccessible = true }
        restoring.setBoolean(null, true)
        try { reject(token) } finally { restoring.setBoolean(null, false) }
        field("activeService").set(null, null)
        reject(token)
        field("activeService").set(null, service)
        coordinator.close()
        reject(token)
    }
}
