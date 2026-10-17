package ru.tgwatch

import java.net.HttpURLConnection
import java.net.URL

typealias HttpProbeMode = ru.tgwatch.probe.HttpProbeMode
internal typealias HttpProbeResult = ru.tgwatch.probe.HttpProbeResult
internal class HttpEndpointProbe(open: (URL) -> HttpURLConnection) {
    private val delegate = ru.tgwatch.probe.HttpEndpointProbe(PhoneProbeAdapter.transport(open) { java.net.Socket() })
    fun check(endpoint: ProbeEndpoint, cancellation: ProbeCancellation): HttpProbeResult =
        delegate.check(endpoint, cancellation)
}
