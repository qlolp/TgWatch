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
        value.latencyMs, ProbeDetail(value.detailCode), value.measurement)
    fun outcome(value: ru.tgwatch.probe.ProbeOutcome) = ProbeOutcome(value.endpoint, value.group, value.reachable,
        value.latencyMs, "", value.measurement, value.detail.code, detail(value.detail))
    fun report(value: ru.tgwatch.probe.ProbeReport) = ProbeReport(value.results.map(::outcome), value.connected)
    fun controls(): List<ProbeEndpoint> = ru.tgwatch.probe.NetworkProbe.controlEndpoints.map {
        if (it.address == "https://ya.ru/") it.copy(name = "Yandex") else it
    }
    fun error(error: Exception): TextValue {
        val structured = ProbeDetail.failure(error)
        return if (structured.code == ProbeDetailCode.EXCEPTION)
            textLiteral(error.javaClass.simpleName.ifEmpty { "Exception" }) else detail(structured)
    }
    fun detail(value: ProbeDetail): TextValue = when (value.code) {
        ProbeDetailCode.NONE -> textLiteral("")
        ProbeDetailCode.HTTP_RESPONSE -> textLiteral("HTTP ${value.httpCode}")
        ProbeDetailCode.HTTP_CODE_MISMATCH, ProbeDetailCode.HTTP_JSON_NOT_CONFIRMED -> textLiteral(
            "HTTP ${value.httpCode}")
        ProbeDetailCode.MTPROTO_CONFIRMED -> textOf(TextKey.COPY_PHONE_PROBE_ADAPTER_MTPROTO_RESPQ_NONCE_MATCHED)
        ProbeDetailCode.MTPROTO_UNCONFIRMED -> textOf(TextKey.COPY_PHONE_PROBE_ADAPTER_MTPROTO_RESPONSE_NOT_CONFIRMED)
        ProbeDetailCode.BUSY -> textOf(TextKey.COPY_PHONE_PROBE_ADAPTER_THE_PREVIOUS_REQUEST_IS)
        ProbeDetailCode.CANCELLED -> textOf(TextKey.COPY_PHONE_PROBE_ADAPTER_CHECK_CANCELLED)
        ProbeDetailCode.TIME_LIMIT -> textOf(TextKey.COPY_PHONE_PROBE_ADAPTER_OVERALL_TIME_LIMIT_EXPIRED)
        ProbeDetailCode.DNS -> textOf(TextKey.COPY_PHONE_PROBE_ADAPTER_DNS_ADDRESS_NOT_FOUND)
        ProbeDetailCode.TLS -> textOf(TextKey.COPY_PHONE_PROBE_ADAPTER_TLS_SECURE_CONNECTION_NOT)
        ProbeDetailCode.TIMEOUT -> textOf(TextKey.COPY_PHONE_PROBE_ADAPTER_TIMEOUT)
        ProbeDetailCode.EXCEPTION -> textLiteral(value.exceptionClass ?: "Exception")
    }
}
