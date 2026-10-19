package ru.tgwatch.wear

import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import ru.tgwatch.probe.EndpointProbe
import ru.tgwatch.probe.MtProtoEndpoints
import ru.tgwatch.probe.NetworkProbe
import ru.tgwatch.probe.ProbeAdmission
import ru.tgwatch.probe.ProbeBatch
import ru.tgwatch.probe.ProbeEndpoint
import ru.tgwatch.probe.ProbeGroup
import ru.tgwatch.probe.ProbeReport
import ru.tgwatch.probe.ProbeTransport

object WatchProbePlan {
    private const val MT_COUNT = 3
    const val NETWORK_BUDGET_MS = 7_500L
    fun endpoints(rotation: Int): List<ProbeEndpoint> {
        val web = NetworkProbe.primaryEndpoints.filter { it.group == ProbeGroup.WEB }
        val dc = MtProtoEndpoints.all
        val first = Math.floorMod(rotation, dc.size) * MT_COUNT % dc.size
        return listOf(web[Math.floorMod(rotation, web.size)]) + List(MT_COUNT) { dc[(first + it) % dc.size] }
    }
}

/** This registry, rather than any Activity or executor generation, owns the four physical permits. */
internal object WatchBudget {
    private const val REQUESTS = 4
    private const val POOL_KEEP_ALIVE_SECONDS = 30L
    val admission = ProbeAdmission(REQUESTS, 0)
    val transportStarts = AtomicLong()
    val pool = ThreadPoolExecutor(REQUESTS, REQUESTS, POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "tg-watch-probe").apply { isDaemon = true } })
}

class WatchProbeRunner(transport: ProbeTransport, private val pool: ExecutorService = WatchBudget.pool,
    private val admission: ProbeAdmission = WatchBudget.admission) {
    private val endpoint = EndpointProbe(object : ProbeTransport by transport {
        override fun openConnection(url: java.net.URL): java.net.HttpURLConnection {
            WatchBudget.transportStarts.incrementAndGet()
            return transport.openConnection(url)
        }
        override fun openSocket(): java.net.Socket {
            WatchBudget.transportStarts.incrementAndGet()
            return transport.openSocket()
        }
    })
    fun check(rotation: Int, timeoutMs: Long = WatchProbePlan.NETWORK_BUDGET_MS): ProbeReport =
        ProbeReport(ProbeBatch(pool, timeoutMs.coerceIn(1L, WatchProbePlan.NETWORK_BUDGET_MS), admission)
            .run(WatchProbePlan.endpoints(rotation), endpoint::probe))
}
