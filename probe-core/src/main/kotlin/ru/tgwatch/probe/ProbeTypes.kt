package ru.tgwatch.probe

import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

enum class ProbeGroup { WEB, MTPROTO, CONTROL, EXTRA }
enum class ProbeMeasurement { SUCCESS, FAILURE, NOT_MEASURED }
data class ProbeEndpoint(val name: String, val group: ProbeGroup, val address: String,
    val httpMode: HttpProbeMode = HttpProbeMode.REACHABLE, val condition: HttpProbeCondition? = null) {
    override fun toString() = "ProbeEndpoint(name=$name, group=$group, address=redacted, httpMode=$httpMode, " +
        "condition=${if (condition == null) "null" else "redacted"})"
}
enum class ProbeDetailCode {
    NONE, HTTP_RESPONSE, MTPROTO_CONFIRMED, MTPROTO_UNCONFIRMED, BUSY, CANCELLED, TIME_LIMIT,
    DNS, TLS, TIMEOUT, EXCEPTION, HTTP_CODE_MISMATCH, HTTP_JSON_NOT_CONFIRMED
}
data class ProbeDetail(val code: ProbeDetailCode, val httpCode: Int? = null, val exceptionClass: String? = null) {
    companion object {
        private const val MAX_EXCEPTION_CLASS_CHARS = 120
        fun failure(error: Exception): ProbeDetail = ProbeDetail(when (error) {
            is UnknownHostException -> ProbeDetailCode.DNS
            is SSLException -> ProbeDetailCode.TLS
            is SocketTimeoutException, is TimeoutException -> ProbeDetailCode.TIMEOUT
            is InterruptedException -> ProbeDetailCode.CANCELLED
            else -> ProbeDetailCode.EXCEPTION
        }, exceptionClass = error.javaClass.simpleName.take(MAX_EXCEPTION_CLASS_CHARS).ifEmpty { "Exception" })
    }
}
data class ProbeOutcome(
    val endpoint: String,
    val group: ProbeGroup,
    val reachable: Boolean,
    val latencyMs: Long = -1,
    val detail: ProbeDetail,
    val measurement: ProbeMeasurement =
        if (reachable) ProbeMeasurement.SUCCESS else ProbeMeasurement.FAILURE,
)

interface ProbeTransport {
    val userAgent: String
    fun openConnection(url: URL): HttpURLConnection
    fun openSocket(): Socket
}

object ProbePolicy {
    private const val HTTP_REACHABLE_FIRST = 100
    private const val HTTP_REACHABLE_LAST = 499
    fun isReachableHttpCode(code: Int) = code in HTTP_REACHABLE_FIRST..HTTP_REACHABLE_LAST
}

/** Classification uses evidence, not absence of permission/admission/measurement. */
data class ProbeReport(val results: List<ProbeOutcome>, val connected: Boolean = true) {
    val publishable: Boolean get() = !connected ||
        results.any { it.group in PRIMARY && it.reachable } || complete(results)
    val status: String get() = classify(results, connected)
    val latencyMs: Long get() = results.filter { it.reachable && it.group == ProbeGroup.MTPROTO }
        .minOfOrNull { it.latencyMs } ?: -1L
    companion object {
        private val PRIMARY = setOf(ProbeGroup.WEB, ProbeGroup.MTPROTO)
        private fun complete(results: List<ProbeOutcome>) = results.any { it.group in PRIMARY } &&
            results.none { it.group in PRIMARY && it.measurement == ProbeMeasurement.NOT_MEASURED }
        fun classify(results: List<ProbeOutcome>, connected: Boolean): String {
            if (!connected) return "NO_NETWORK"
            val web = results.any { it.group == ProbeGroup.WEB && it.reachable }
            val mt = results.any { it.group == ProbeGroup.MTPROTO && it.reachable }
            return when {
                web && mt -> "OK"
                web || mt -> "PARTIAL"
                !complete(results) -> "UNKNOWN"
                results.any { it.group == ProbeGroup.CONTROL && it.reachable } -> "TG_DOWN"
                else -> "UNKNOWN"
            }
        }
    }
}
