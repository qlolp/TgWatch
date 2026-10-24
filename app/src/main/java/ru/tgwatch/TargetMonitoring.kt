package ru.tgwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.SystemClock
import java.io.Closeable
import java.net.HttpURLConnection
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

internal interface TargetMonitorHost {
    val context: Context
    val worker: Handler
    val primaryPool: ExecutorService
    val secondaryPool: ExecutorService
    fun isCurrent(): Boolean
    fun acquireWakeLock()
    fun onProgress()
    fun onPublished(snapshot: TargetSnapshot, report: TargetReport)
    fun onUnmeasured(id: TargetId) {}
}

/** Android network/configuration adapter. The primary owner invokes this after Telegram publication. */
internal class TargetMonitoring(internal val host: TargetMonitorHost,
    powerStateReader: (() -> TargetPowerState)? = null,
    private val runnerFactory: ((Network) -> TargetRunner)? = null) : Closeable {
    private val cm = host.context.getSystemService(ConnectivityManager::class.java)
    val repository = TargetRepository(TargetPreferences.definitions(host.context),
        elapsedClock = SystemClock::elapsedRealtime)
    private val schedule = TargetSchedule(repository.definitions().filter { it.id != TargetId.TELEGRAM })
    @Volatile private var closed = false
    private val settingsRevisions = mutableMapOf<TargetId, Long>()
    private val dispatch = TargetDispatch(Executor { host.worker.post(it) }) {
        if (!closed && host.isCurrent()) host.onProgress()
    }
    val hasInFlight: Boolean get() = dispatch.busy
    val telegramEnabled: Boolean get() = repository.configuration(TargetId.TELEGRAM).definition.enabled
    private val power = TargetPowerMonitor(host.context, {
        host.worker.post {
            if (!closed && host.isCurrent() && TargetPowerSettings.enabled(host.context)) {
                applyPower(); host.onProgress()
            }
        }
    }, powerStateReader)
    init { refresh(notify = false) }

    fun refresh(notify: Boolean = true) {
        synchronized(TargetPreferences) {
            TargetPreferences.definitions(host.context).forEach { definition ->
                val before = repository.configuration(definition.id)
                val revision = TargetPreferences.endpointRevision(host.context, definition.id)
                val settingsRevision = TargetPreferences.fence.snapshot(definition.id).revision
                val settingsChanged = settingsRevisions.put(definition.id, settingsRevision)?.let {
                    it != settingsRevision
                } ?: false
                if (before.definition != definition || before.endpointRevision != revision || settingsChanged) {
                    repository.install(TargetConfiguration(definition, Math.addExact(before.generation, 1L), revision))
                    if (definition.id != TargetId.TELEGRAM) {
                        schedule.configure(definition, SystemClock.elapsedRealtime())
                        schedule.request(definition.id, SystemClock.elapsedRealtime())
                        dispatch.cancel(definition.id)
                    }
                }
            }
        }
        applyPower()
        if (notify && !closed && host.isCurrent()) host.onProgress()
    }

    fun networkChanged(revision: Long) {
        repository.networkChanged(revision)
        dispatch.cancelAll()
        repository.definitions().filter { it.id != TargetId.TELEGRAM }.forEach {
            schedule.request(it.id, SystemClock.elapsedRealtime())
        }
    }

    fun startDue() {
        if (closed || !host.isCurrent()) return
        refresh(notify = false)
        val due = schedule.due(SystemClock.elapsedRealtime())
        if (due.isNotEmpty()) host.acquireWakeLock()
        due.forEach(::launch)
        if (due.isNotEmpty() && !closed && host.isCurrent()) host.onProgress()
    }

    fun onTelegram(state: MonitorService.State, outcomes: List<ProbeOutcome>) {
        if (closed || !host.isCurrent()) return
        refresh(notify = false)
        val cfg = repository.configuration(TargetId.TELEGRAM)
        val revision = Math.addExact(repository.snapshot(TargetId.TELEGRAM)?.revision ?: -1L, 1L)
        val snapshot = TargetSnapshot.fromTelegram(state, cfg.generation, revision, MonitorService.healthSnapshot())
            .copy(endpointRevision = cfg.endpointRevision)
        val report = TargetReport(snapshot.status, outcomes.toList(), true, snapshot.latencyMs)
        if (repository.publish(snapshot)) host.onPublished(snapshot, report)
    }

    fun request(id: TargetId): Boolean {
        if (closed || !host.isCurrent()) return false
        return schedule.request(id, SystemClock.elapsedRealtime()).also { if (it) host.onProgress() }
    }
    fun nextWakeElapsed(): Long? = schedule.nextWakeElapsed()
    val expectedIntervals: Map<TargetId, Int> get() = repository.definitions()
        .filter { it.id != TargetId.TELEGRAM }.associate {
            it.id to TargetPowerPolicy.intervalSec(it.intervalSec,
                TargetPowerSettings.enabled(host.context), power.state)
        }
    private fun applyPower() = expectedIntervals.forEach { (id, seconds) ->
        schedule.setInterval(id, seconds, SystemClock.elapsedRealtime())
    }
    override fun close() { closed = true; power.close(); dispatch.close() }

    private fun launch(id: TargetId) {
        val cfg = repository.configuration(id)
        val privateConfig = if (cfg.definition.requiresEndpoint)
            TargetPreferences.configuration(host.context, id) else null
        val endpoint = privateConfig?.url
        val parked = cfg.definition.requiresEndpoint && endpoint == null
        if (!cfg.definition.enabled || parked || closed) {
            schedule.finished(id, SystemClock.elapsedRealtime())
            return
        }
        val network = cm.activeNetwork
        val caps = network?.let(cm::getNetworkCapabilities)
        val attempt = TargetAttempt(id, cfg.generation, repository.currentNetworkRevision,
            cfg.endpointRevision, SystemClock.elapsedRealtime(), expectedIntervals.getValue(id))
        dispatch.submit(attempt, {
            val report = when {
                network == null || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true ->
                    TargetReport(TargetStatus.NO_NETWORK, emptyList(), true, null)
                caps == null -> TargetReport(TargetStatus.UNKNOWN, emptyList(), false, null)
                else -> (runnerFactory?.invoke(network) ?: secondaryRunner(host, network))
                    .check(attempt, cfg.definition, endpoint, privateConfig?.condition)
            }
            report.copy(networkKind = caps?.let(WebhookNetworks::kind) ?: NetworkKind.UNKNOWN,
                checkedAtMs = System.currentTimeMillis(), checkedElapsedMs = SystemClock.elapsedRealtime())
        }, { completed(attempt, network, it) })
    }

    private fun completed(attempt: TargetAttempt, network: Network?, result: Result<TargetReport>) {
        try {
            val report = result.getOrNull()
            var published: TargetSnapshot? = null
            if (!closed && host.isCurrent()) synchronized(TargetPreferences) {
                refresh(notify = false)
                val configuration = repository.configuration(attempt.id)
                val current = network == cm.activeNetwork &&
                    attempt.networkRevision == repository.currentNetworkRevision &&
                    attempt.generation == configuration.generation &&
                    attempt.endpointRevision == configuration.endpointRevision
                if (current) {
                    val actual = attempt.copy(expectedIntervalSec = expectedIntervals.getValue(attempt.id))
                    if (report != null && repository.publish(actual, report))
                        published = repository.snapshot(attempt.id)
                    else if (report?.publishable != true) host.onUnmeasured(attempt.id)
                }
            }
            published?.let { host.onPublished(it, checkNotNull(report)) }
        } finally {
            schedule.setInterval(attempt.id, expectedIntervals.getValue(attempt.id),
                SystemClock.elapsedRealtime())
            schedule.finished(attempt.id, SystemClock.elapsedRealtime())
        }
    }

}

private fun secondaryRunner(host: TargetMonitorHost, network: Network): TargetRunner {
    val probe = NetworkProbe(host.primaryPool, host.secondaryPool,
        { url -> network.openConnection(url) as HttpURLConnection }, { network.socketFactory.createSocket() })
    return TargetRunner({ error("Telegram belongs to the primary owner") }, probe::checkSecondary)
}
