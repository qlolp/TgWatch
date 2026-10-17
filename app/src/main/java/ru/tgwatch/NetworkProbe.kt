package ru.tgwatch

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService

typealias ProbeGroup = ru.tgwatch.probe.ProbeGroup
typealias ProbeMeasurement = ru.tgwatch.probe.ProbeMeasurement
typealias ProbeEndpoint = ru.tgwatch.probe.ProbeEndpoint
typealias ProbeCancellation = ru.tgwatch.probe.ProbeCancellation

/** Phone display DTO: localization stays outside the shared protocol/transport implementation. */
data class ProbeOutcome(val endpoint: String, val group: ProbeGroup, val reachable: Boolean,
    val latencyMs: Long = -1, val detail: String,
    val measurement: ProbeMeasurement = if (reachable) ProbeMeasurement.SUCCESS else ProbeMeasurement.FAILURE)

data class ProbeReport(val results: List<ProbeOutcome>, val connected: Boolean = true) {
    private val core = ru.tgwatch.probe.ProbeReport(results.map(PhoneProbeAdapter::core), connected)
    val publishable: Boolean get() = core.publishable
    val status: String get() = core.status
    val latencyMs: Long get() = core.latencyMs
    val reason get() = when (status) {
        "OK" -> Lang.t("MTProto и веб-ресурсы отвечают; доставка сообщений не проверяется", "MTProto and web resources are responding; message delivery is not checked")
        "PARTIAL" -> if (results.any { it.group == ProbeGroup.MTPROTO && it.reachable })
            Lang.t("MTProto отвечает, веб-ресурсы не подтвердили доступность", "MTProto is responding; web resources did not confirm reachability") else
            Lang.t("Веб-ресурсы отвечают, MTProto не подтвердил доступность", "Web resources are responding; MTProto did not confirm reachability")
        "TG_DOWN" -> Lang.t("Контрольные сайты отвечают, Telegram не отвечает", "Control sites are responding; Telegram is not")
        "NO_NETWORK" -> Lang.t("Нет активной сети или требуется вход в Wi-Fi", "No active network, or Wi-Fi sign-in is required")
        else -> Lang.t("Доступность не определена: ни один адрес не подтвердил связь", "Reachability unknown: no address confirmed a connection")
    }
    companion object {
        fun classify(results: List<ProbeOutcome>, connected: Boolean): String =
            ru.tgwatch.probe.ProbeReport.classify(results.map(PhoneProbeAdapter::core), connected)
    }
}

/** The adapter keeps injected phone DTOs while delegating physical ownership to the one core registry. */
class ProbeBatch(pool: ExecutorService, timeoutMs: Long = 6_000L,
    admission: ProbeAdmission = ProbeAdmission.process, lane: ProbeLane = ProbeLane.PRIMARY) {
    private val delegate = ru.tgwatch.probe.ProbeBatch(pool, timeoutMs, admission, lane)
    fun run(endpoints: List<ProbeEndpoint>, operation: (ProbeEndpoint, ProbeCancellation) -> ProbeOutcome): List<ProbeOutcome> =
        delegate.runMapped(endpoints, { endpoint, detail, measurement ->
            ProbeOutcome(endpoint.name, endpoint.group, false, -1L, PhoneProbeAdapter.detail(detail), measurement)
        }, operation)
}

fun describeProbeError(error: Exception): String = PhoneProbeAdapter.error(error)

class NetworkProbe(pool: ExecutorService, extraPool: ExecutorService,
    openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    openSocket: () -> java.net.Socket = { java.net.Socket() }) {
    private val delegate = ru.tgwatch.probe.NetworkProbe(pool, extraPool,
        PhoneProbeAdapter.transport(openConnection, openSocket), controls = PhoneProbeAdapter.controls())
    fun check(extras: List<ProbeEndpoint> = emptyList()): ProbeReport = PhoneProbeAdapter.report(delegate.check(extras))
    fun checkPrimary(): ProbeReport = PhoneProbeAdapter.report(delegate.checkPrimary())
    fun checkExtras(extras: List<ProbeEndpoint>): List<ProbeOutcome> =
        delegate.checkExtras(extras).map(PhoneProbeAdapter::outcome)
    fun checkSecondary(endpoints: List<ProbeEndpoint>, timeoutMs: Long): List<ProbeOutcome> =
        delegate.checkSecondary(endpoints, timeoutMs).map(PhoneProbeAdapter::outcome)
    companion object {
        fun executor(): ExecutorService = ru.tgwatch.probe.NetworkProbe.executor()
        fun extraExecutor(): ExecutorService = ru.tgwatch.probe.NetworkProbe.extraExecutor()
    }
}
