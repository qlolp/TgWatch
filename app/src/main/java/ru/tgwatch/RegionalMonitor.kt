package ru.tgwatch

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

internal object RegionalStateStore {
    private val results = mutableMapOf<RegionalService, RegionalResult>()
    @Synchronized fun get(service: RegionalService): RegionalResult? = results[service]
    @Synchronized fun put(result: RegionalResult) { results[result.service] = result }
    @Synchronized fun clear() { results.clear() }
}

internal fun MonitorService.Companion.requestRegionalCheck(): Boolean = regionalRequest?.invoke() == true

internal fun nextMonitoringDelay(primary: Long?, targets: TargetMonitoring, regional: RegionalMonitor?): Long? =
    CheckAlarm.nextDelay(SystemClock.elapsedRealtime(), primary,
        listOfNotNull(targets.nextWakeElapsed(), regional?.nextWakeElapsed).minOrNull())

/** One physical worker per process, including service restart and transports that ignore cancellation. */
private object RegionalWorkers {
    val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "regional-check").apply { isDaemon = true } })
}

internal class RegionalMonitor(private val ctx: Context, private val active: () -> Boolean,
    private val progress: () -> Unit,
    private val probe: (RegionalService, android.net.Network, ProbeCancellation, Long, Long) -> RegionalResult =
        RegionalHttpProbe::check) {
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val notifications = RegionalNotifications(ctx)
    private val due = mutableMapOf<RegionalService, Long>()
    private val consensus = mutableMapOf<RegionalService, RegionalConsensus>()
    private var revision = 0L
    private var configuration = -1L
    private var ticket = 0L
    private var cancellation: ProbeCancellation? = null
    private var closed = false
    val intervalMs: Long get() = if (!power.isInteractive || power.isPowerSaveMode) SLEEP_MS else INTERVAL_MS

    @Synchronized fun changed() {
        revision++
        ticket++
        cancellation?.close()
        cancellation = null
        due.clear()
        consensus.clear()
        RegionalStateStore.clear()
        notifications.clearAll()
    }

    @Synchronized fun request(): Boolean {
        val allowed = !closed && active() && RegionalPreferences.anyEnabled(ctx)
        if (allowed) { RegionalService.entries.forEach { due[it] = 0L }; tick(); progress() }
        return allowed
    }

    @Synchronized fun tick() {
        if (closed || !active()) return
        val config = RegionalPreferences.revision(ctx)
        if (configuration != config) { changed(); configuration = config }
        val now = SystemClock.elapsedRealtime()
        reconcile(now)
        if (cancellation != null) return
        startDue(now, config)
    }

    private fun startDue(now: Long, config: Long) {
        val service = RegionalService.entries.firstOrNull {
            RegionalPreferences.enabled(ctx, it) && (due[it] ?: 0L) <= now
        } ?: return
        val network = connectivity.activeNetwork
        due[service] = now + if (network == null) RETRY_MS else intervalMs
        if (network != null) startProbe(service, network, now, config)
    }

    private fun startProbe(service: RegionalService, network: android.net.Network, now: Long, config: Long) {
        val permit = ProbeAdmission.process.tryAcquire("regional_services", ProbeLane.SECONDARY) ?: run {
            due[service] = now + RETRY_MS
            return
        }
        val cancel = ProbeCancellation()
        cancellation = cancel
        val captured = ++ticket
        val networkRevision = revision
        try {
            RegionalWorkers.executor.execute {
                try {
                    if (permit.start()) {
                        val result = probe(service, network, cancel, networkRevision, config)
                        main.post { complete(captured, result) }
                    }
                } finally { permit.finished(); cancel.close() }
            }
        } catch (_: RejectedExecutionException) {
            permit.cancelBeforeStart()
            cancellation = null
            due[service] = now + RETRY_MS
        }
    }

    @Synchronized private fun complete(captured: Long, result: RegionalResult) {
        if (captured != ticket || closed) return
        cancellation = null
        if (!active() || !current(result)) return
        RegionalStateStore.put(result)
        val evidence = consensus.getOrPut(result.service) { RegionalConsensus() }
        evidence.update(result.status, "${result.networkHandle}:$revision:$configuration", result.checkedElapsed)
        if (result.status == RegionalStatus.WEB_REACHABLE) RegionalPreferences.reachable(ctx, result.service)
        if (result.status != RegionalStatus.REGION_RESTRICTED) notifications.clear(result.service)
        if (result.status == RegionalStatus.REGION_RESTRICTED && !evidence.confirmed())
            due[result.service] = result.checkedElapsed + RegionalConsensus.CONFIRM_MS
        reconcile(SystemClock.elapsedRealtime())
        progress()
    }

    private fun current(result: RegionalResult): Boolean = identityMatches(result) &&
        RegionalPreferences.enabled(ctx, result.service)

    private fun identityMatches(result: RegionalResult): Boolean = result.networkRevision == revision &&
        result.configurationRevision == RegionalPreferences.revision(ctx) &&
        result.networkHandle == connectivity.activeNetwork?.networkHandle

    private fun reconcile(now: Long) {
        RegionalService.entries.forEach { service ->
            val result = RegionalStateStore.get(service) ?: return@forEach
            val evidence = consensus[service] ?: return@forEach
            if (now - result.checkedElapsed > intervalMs * 2 || !RegionalPreferences.enabled(ctx, service)) {
                notifications.clear(service)
                return@forEach
            }
            if (evidence.shouldNotify(now) && RegionalPreferences.notificationAllowed(ctx, service,
                    result.networkHandle) && notifications.post(result)) {
                evidence.recordNotification(now)
                RegionalPreferences.delivered(ctx, service, result.networkHandle)
            }
        }
    }

    val nextWakeElapsed: Long? get() = synchronized(this) {
        if (closed || cancellation != null) null else RegionalService.entries
            .filter { RegionalPreferences.enabled(ctx, it) }.minOfOrNull { due[it] ?: 0L }
    }

    @Synchronized fun close() { closed = true; changed() }
    companion object {
        const val INTERVAL_MS = 300_000L
        const val SLEEP_MS = 600_000L
        private const val RETRY_MS = 30_000L
    }
}
