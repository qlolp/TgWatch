package ru.tgwatch

import android.net.Network
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** Fixed unauthenticated HTTPS endpoints. Default platform certificate/hostname validation stays enabled. */
internal object RegionalHttpProbe {
    private const val TIMEOUT_MS = 3_000
    private const val BUDGET_MS = 9_000L
    private const val MAX_BYTES = 32 * 1024
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "regional-deadline").apply { isDaemon = true }
    }

    fun check(service: RegionalService, network: Network, cancellation: ProbeCancellation,
        networkRevision: Long, configurationRevision: Long): RegionalResult {
        val deadline = deadlines.schedule({ cancellation.close() }, BUDGET_MS, TimeUnit.MILLISECONDS)
        val measured = try { request(service, network, cancellation) }
        catch (_: UnknownHostException) { failure(RegionalFailure.DNS) }
        catch (_: SSLException) { failure(RegionalFailure.TLS) }
        catch (_: SocketTimeoutException) { failure(RegionalFailure.TIMEOUT) }
        catch (_: InterruptedException) { failure(RegionalFailure.TIMEOUT) }
        catch (_: IOException) { failure(RegionalFailure.CONNECTION) }
        catch (_: IllegalArgumentException) { failure(RegionalFailure.UNMEASURED) }
        finally { deadline.cancel(false) }
        return RegionalResult(service, measured.status, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            network.networkHandle, networkRevision, configurationRevision, measured.code, measured.failure)
    }

    private data class Measured(val status: RegionalStatus, val code: Int? = null,
        val failure: RegionalFailure? = null)
    private fun failure(failure: RegionalFailure) = Measured(RegionalStatus.NETWORK_ERROR, failure = failure)

    private fun request(service: RegionalService, network: Network,
        cancellation: ProbeCancellation): Measured {
        var uri = URI(service.address)
        repeat(3) { hop ->
            cancellation.check()
            val connection = network.openConnection(uri.toURL()) as HttpsURLConnection
            cancellation.register { connection.disconnect() }
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "text/html, application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cookie", "")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")?.takeIf { it.length <= 2_048 }
                    val next = location?.let { runCatching { uri.resolve(it) }.getOrNull() }
                    val status = RegionalClassifier.classify(code, "", "", next?.host.orEmpty())
                    if (status == RegionalStatus.AUTH_REQUIRED) return Measured(status, code)
                    if (hop == 2 || next == null || !allowed(service, next))
                        return Measured(RegionalStatus.UNKNOWN, code)
                    uri = next
                } else {
                    val input = if (code >= 400) connection.errorStream else connection.inputStream
                    val bytes = ByteArrayOutputStream()
                    var complete = true
                    input?.use { stream ->
                        val buffer = ByteArray(2_048)
                        while (true) {
                            cancellation.check()
                            val count = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - bytes.size()))
                            if (count < 0) break
                            bytes.write(buffer, 0, count)
                            if (bytes.size() > MAX_BYTES) { complete = false; break }
                        }
                    }
                    cancellation.check()
                    val body = bytes.toByteArray().toString(Charsets.UTF_8)
                    return Measured(RegionalClassifier.classify(code, connection.contentType.orEmpty(), body,
                        complete = complete), code)
                }
            } finally { connection.disconnect() }
        }
        return Measured(RegionalStatus.UNKNOWN)
    }

    private fun allowed(service: RegionalService, uri: URI): Boolean = uri.scheme == "https" &&
        uri.host in service.canonicalHosts && uri.userInfo == null && uri.port in setOf(-1, 443)
}
