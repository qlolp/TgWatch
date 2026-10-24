package ru.tgwatch.probe

import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Shared transport runner; Android Network binding and localization belong to the caller. */
class EndpointProbe(private val transport: ProbeTransport) {
    fun probe(endpoint: ProbeEndpoint, cancellation: ProbeCancellation): ProbeOutcome {
        val start = System.nanoTime()
        if (endpoint.group == ProbeGroup.MTPROTO) {
            val ok = MtProto.probe(endpoint.address, transport::openSocket, cancellation)
            return ProbeOutcome(endpoint.name, endpoint.group, ok, elapsed(start), ProbeDetail(
                if (ok) ProbeDetailCode.MTPROTO_CONFIRMED else ProbeDetailCode.MTPROTO_UNCONFIRMED))
        }
        val http = HttpEndpointProbe(transport).check(endpoint, cancellation)
        return ProbeOutcome(endpoint.name, endpoint.group, http.confirmed, elapsed(start),
            ProbeDetail(when (http.failure) {
                HttpConditionFailure.CODE_MISMATCH -> ProbeDetailCode.HTTP_CODE_MISMATCH
                HttpConditionFailure.JSON_NOT_CONFIRMED -> ProbeDetailCode.HTTP_JSON_NOT_CONFIRMED
                null -> ProbeDetailCode.HTTP_RESPONSE
            }, http.code))
    }
    private fun elapsed(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
}

class NetworkProbe(private val pool: ExecutorService, private val extraPool: ExecutorService,
    transport: ProbeTransport, private val telegram: List<ProbeEndpoint> = primaryEndpoints,
    private val controls: List<ProbeEndpoint> = controlEndpoints) {
    private val endpointProbe = EndpointProbe(transport)
    init { require(pool !== extraPool) { "EXTRA probes require a separate executor" } }
    fun check(extras: List<ProbeEndpoint> = emptyList()): ProbeReport {
        val primary = checkPrimary()
        return primary.copy(results = primary.results + checkExtras(extras))
    }
    fun checkExtras(extras: List<ProbeEndpoint>): List<ProbeOutcome> =
        checkSecondary(extras.filter { it.group == ProbeGroup.EXTRA }, EXTRA_TIMEOUT_MS)
    fun checkSecondary(endpoints: List<ProbeEndpoint>, timeoutMs: Long): List<ProbeOutcome> =
        ProbeBatch(extraPool, timeoutMs, lane = ProbeLane.SECONDARY)
            .run(
                endpoints.filter { it.group == ProbeGroup.EXTRA || it.group == ProbeGroup.CONTROL },
                endpointProbe::probe,
            )
    fun checkPrimary(): ProbeReport {
        val results = ProbeBatch(pool).run(telegram, endpointProbe::probe).toMutableList()
        if (results.none { it.reachable }) results += ProbeBatch(pool).run(controls, endpointProbe::probe)
        return ProbeReport(results)
    }
    companion object {
        private const val PRIMARY_WORKERS = 16
        private const val SECONDARY_WORKERS = 3
        private const val EXTRA_TIMEOUT_MS = 5_000L
        val primaryEndpoints = listOf(
            ProbeEndpoint("api.telegram.org", ProbeGroup.WEB, "https://api.telegram.org/"),
            ProbeEndpoint("web.telegram.org", ProbeGroup.WEB, "https://web.telegram.org/"),
            ProbeEndpoint("core.telegram.org", ProbeGroup.WEB, "https://core.telegram.org/"),
        ) + MtProtoEndpoints.all
        val controlEndpoints = listOf(
            ProbeEndpoint("Yandex", ProbeGroup.CONTROL, "https://ya.ru/"),
            ProbeEndpoint("Mail.ru", ProbeGroup.CONTROL, "https://mail.ru/"),
            ProbeEndpoint("Google 204", ProbeGroup.CONTROL, "https://www.gstatic.com/generate_204"),
        )
        fun executor(): ExecutorService = executor(PRIMARY_WORKERS, "tg-probe")
        fun extraExecutor(): ExecutorService = executor(SECONDARY_WORKERS, "tg-extra-probe")
        private fun executor(size: Int, name: String): ExecutorService =
            ThreadPoolExecutor(size, size, 30L, TimeUnit.SECONDS, SynchronousQueue(),
                { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
