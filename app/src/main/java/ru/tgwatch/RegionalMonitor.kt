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
import java.util.concurrent.Executor

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

/** Production always shares one process registry/pool; deterministic fixtures can own their execution boundary. */
internal class RegionalExecution(val admission: ProbeAdmission, val executor: Executor) {
    companion object {
        val process = RegionalExecution(ProbeAdmission.process, RegionalWorkers.executor)
    }
}

internal class RegionalMonitor(private val ctx: Context, private val active: () -> Boolean,
    private val progress: () -> Unit,
    private val probe: (RegionalService, android.net.Network, ProbeCancellation, Long, Long) -> RegionalResult =
        RegionalHttpProbe::check, private val wake: () -> Unit = {},
    private val execution: RegionalExecution = RegionalExecution.process) {
    constructor(host: TargetMonitorHost) : this(host.context, host::isCurrent,
        { host.worker.post { if (host.isCurrent()) host.onProgress() } }, wake = host::acquireWakeLock)
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val notifications = RegionalNotifications(ctx)
    private val watches = RegionalWatchController(ctx, active)
    private val due = mutableMapOf<RegionalService, Long>()
    private val consensus = mutableMapOf<RegionalService, RegionalConsensus>()
    private var revision = 0L
    private var configuration = -1L
    private var ticket = 0L
    private var cancellation: ProbeCancellation? = null
    private var closed = false
    val intervalMs: Long get() = if (!power.isInteractive || power.isPowerSaveMode) SLEEP_MS else INTERVAL_MS
    val hasInFlight: Boolean get() = synchronized(this) { cancellation != null }

    @Synchronized fun changed() {
        revision++
        ticket++
        cancellation?.close()
        cancellation = null
        due.clear()
        consensus.clear()
        watches.resetEvidence()
        RegionalStateStore.clear()
        RegionalContext.invalidate()
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
        val permit = execution.admission.tryAcquire("regional_services", ProbeLane.SECONDARY) ?: run {
            due[service] = now + RETRY_MS
            return
        }
        val cancel = ProbeCancellation()
        val journalGeneration = RegionalRepository.generation(ctx)
        cancellation = cancel
        val captured = ++ticket
        val networkRevision = revision
        wake()
        try {
            execution.executor.execute {
                try {
                    if (permit.start()) {
                        val result = probe(service, network, cancel, networkRevision, config)
                        main.post { complete(captured, result, journalGeneration) }
                    }
                } finally { permit.finished(); cancel.close() }
            }
        } catch (_: RejectedExecutionException) {
            permit.cancelBeforeStart()
            cancellation = null
            due[service] = now + RETRY_MS
            progress()
        }
    }

    @Synchronized private fun complete(captured: Long, result: RegionalResult, journalGeneration: Long) {
        if (captured != ticket || closed) return
        cancellation = null
        if (!active() || !current(result)) return
        val evidence = consensus.getOrPut(result.service) { RegionalConsensus() }
        evidence.update(result.status, "${result.networkHandle}:$revision:$configuration", result.checkedElapsed)
        val published = result.copy(regionConfirmed = evidence.confirmed())
        RegionalStateStore.put(published)
        RegionalJournalObserver.record(ctx, published, journalGeneration)
        if (result.status == RegionalStatus.WEB_REACHABLE) RegionalPreferences.reachable(ctx, result.service)
        if (result.status != RegionalStatus.REGION_RESTRICTED) notifications.clear(result.service)
        if (result.status == RegionalStatus.REGION_RESTRICTED && !evidence.confirmed())
            due[result.service] = result.checkedElapsed + RegionalConsensus.CONFIRM_MS
        val now = SystemClock.elapsedRealtime()
        watches.observe(published, now, intervalMs * 2)?.let { next ->
            due[result.service] = minOf(due[result.service] ?: next, next)
        }
        reconcile(now)
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
            if (now < result.checkedElapsed || now - result.checkedElapsed > intervalMs * 2 || !current(result)) {
                notifications.clear(service)
                watches.resetEvidence(service)
                return@forEach
            }
            watches.reconcile(result, now, intervalMs * 2)
            val evidence = consensus[service] ?: return@forEach
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
