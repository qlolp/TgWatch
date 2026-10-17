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
}

/** Android network/configuration adapter. The primary owner invokes this after Telegram publication. */
internal class TargetMonitoring(private val host: TargetMonitorHost,
    private val runnerFactory: ((Network) -> TargetRunner)? = null) : Closeable {
    private val cm = host.context.getSystemService(ConnectivityManager::class.java)
    val repository = TargetRepository(TargetPreferences.definitions(host.context),
        elapsedClock = SystemClock::elapsedRealtime)
    private val schedule = TargetSchedule(repository.definitions().filter { it.id != TargetId.TELEGRAM })
    @Volatile private var closed = false
    private val dispatch = TargetDispatch(Executor { host.worker.post(it) }) {
        if (!closed && host.isCurrent()) host.onProgress()
    }
    val hasInFlight: Boolean get() = dispatch.busy
    val telegramEnabled: Boolean get() = repository.configuration(TargetId.TELEGRAM).definition.enabled
    init { refresh(notify = false) }

    fun refresh(notify: Boolean = true) {
        synchronized(TargetPreferences) {
            TargetPreferences.definitions(host.context).forEach { definition ->
                val before = repository.configuration(definition.id)
                val revision = TargetPreferences.endpointRevision(host.context, definition.id)
                if (before.definition != definition || before.endpointRevision != revision) {
                    repository.install(TargetConfiguration(definition, Math.addExact(before.generation, 1L), revision))
                    if (definition.id != TargetId.TELEGRAM) {
                        schedule.configure(definition, SystemClock.elapsedRealtime())
                        schedule.request(definition.id, SystemClock.elapsedRealtime())
                        dispatch.cancel(definition.id)
                    }
                }
            }
        }
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
        val report = TargetReport(snapshot.status, outcomes.toList(), true, snapshot.latencyMs)
        if (repository.publish(snapshot)) host.onPublished(snapshot, report)
    }

    fun request(id: TargetId): Boolean {
        if (closed || !host.isCurrent()) return false
        return schedule.request(id, SystemClock.elapsedRealtime()).also { if (it) host.onProgress() }
    }
    fun nextWakeElapsed(): Long? = schedule.nextWakeElapsed()
    override fun close() { closed = true; dispatch.close() }

    private fun launch(id: TargetId) {
        val (cfg, endpoint) = synchronized(TargetPreferences) {
            val current = repository.configuration(id)
            current to if (current.definition.requiresEndpoint) TargetPreferences.endpoint(host.context, id) else null
        }
        val parked = cfg.definition.requiresEndpoint && endpoint == null
        if (!cfg.definition.enabled || parked || closed) {
            schedule.finished(id, SystemClock.elapsedRealtime())
            return
        }
        val network = cm.activeNetwork
        val caps = network?.let(cm::getNetworkCapabilities)
        val attempt = TargetAttempt(id, cfg.generation, repository.currentNetworkRevision,
            cfg.endpointRevision, SystemClock.elapsedRealtime())
        dispatch.submit(attempt, {
            when {
                network == null || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true ->
                    TargetReport(TargetStatus.NO_NETWORK, emptyList(), true, null)
                caps == null -> TargetReport(TargetStatus.UNKNOWN, emptyList(), false, null)
                else -> (runnerFactory?.invoke(network) ?: runner(network)).check(attempt, cfg.definition, endpoint)
            }
        }, { completed(attempt, network, it) })
    }

    private fun completed(attempt: TargetAttempt, network: Network?, result: Result<TargetReport>) {
        try {
            val report = result.getOrNull()
            var published: TargetSnapshot? = null
            if (!closed && host.isCurrent() && report != null) synchronized(TargetPreferences) {
                refresh(notify = false)
                if (network == cm.activeNetwork && repository.publish(attempt, report))
                    published = repository.snapshot(attempt.id)
            }
            published?.let { host.onPublished(it, checkNotNull(report)) }
        } finally { schedule.finished(attempt.id, SystemClock.elapsedRealtime()) }
    }

    private fun runner(network: Network): TargetRunner {
        val probe = NetworkProbe(host.primaryPool, host.secondaryPool,
            { url -> network.openConnection(url) as HttpURLConnection }, { network.socketFactory.createSocket() })
        return TargetRunner({ error("Telegram belongs to the primary owner") }, probe::checkSecondary)
    }
}
