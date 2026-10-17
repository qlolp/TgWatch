package ru.tgwatch

import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import ru.tgwatch.probe.ProbeDetail
import ru.tgwatch.probe.ProbeDetailCode
import ru.tgwatch.probe.ProbeTransport

/** Android callers inject Network.openConnection/socketFactory through this localized boundary. */
internal object PhoneProbeAdapter {
    fun transport(open: (URL) -> HttpURLConnection, socket: () -> Socket): ProbeTransport = object : ProbeTransport {
        override val userAgent = "TgWatch/${BuildConfig.VERSION_NAME} (Android)"
        override fun openConnection(url: URL) = open(url)
        override fun openSocket() = socket()
    }
    fun core(value: ProbeOutcome) = ru.tgwatch.probe.ProbeOutcome(value.endpoint, value.group, value.reachable,
        value.latencyMs, ProbeDetail(ProbeDetailCode.NONE), value.measurement)
    fun outcome(value: ru.tgwatch.probe.ProbeOutcome) = ProbeOutcome(value.endpoint, value.group, value.reachable,
        value.latencyMs, detail(value.detail), value.measurement)
    fun report(value: ru.tgwatch.probe.ProbeReport) = ProbeReport(value.results.map(::outcome), value.connected)
    fun controls(): List<ProbeEndpoint> = ru.tgwatch.probe.NetworkProbe.controlEndpoints.map {
        if (it.address == "https://ya.ru/") it.copy(name = Lang.t("Яндекс", "Yandex")) else it
    }
    fun error(error: Exception): String {
        val structured = ProbeDetail.failure(error)
        return if (structured.code == ProbeDetailCode.EXCEPTION)
            "${error.javaClass.simpleName}: ${error.message.orEmpty().take(MAX_MESSAGE)}" else detail(structured)
    }
    fun detail(value: ProbeDetail): String = when (value.code) {
        ProbeDetailCode.NONE -> ""
        ProbeDetailCode.HTTP_RESPONSE -> "HTTP ${value.httpCode}"
        ProbeDetailCode.MTPROTO_CONFIRMED -> Lang.t("MTProto resPQ; nonce совпал", "MTProto resPQ; nonce matched")
        ProbeDetailCode.MTPROTO_UNCONFIRMED -> Lang.t("Ответ MTProto не подтверждён", "MTProto response not confirmed")
        ProbeDetailCode.BUSY -> Lang.t("Предыдущий запрос ещё завершается", "The previous request is still finishing")
        ProbeDetailCode.CANCELLED -> Lang.t("Проверка отменена", "Check cancelled")
        ProbeDetailCode.TIME_LIMIT -> Lang.t("Общий срок ожидания истёк", "Overall time limit expired")
        ProbeDetailCode.DNS -> Lang.t("DNS: адрес не найден", "DNS: address not found")
        ProbeDetailCode.TLS -> Lang.t("TLS: защищённое соединение не установлено",
            "TLS: secure connection not established")
        ProbeDetailCode.TIMEOUT -> Lang.t("Таймаут", "Timeout")
        ProbeDetailCode.EXCEPTION -> value.exceptionClass ?: "Exception"
    }
    private const val MAX_MESSAGE = 120
}
