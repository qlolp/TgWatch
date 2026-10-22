package ru.tgwatch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import ru.tgwatch.probe.ProbeCancellation

/** Fixed unauthenticated HTTPS endpoints. Default platform certificate/hostname validation stays enabled. */
internal class RegionalHttpTransport(private val openConnection: (URI) -> HttpsURLConnection) {
    private companion object {
        private const val TIMEOUT_MS = 3_000
        private const val BUDGET_MS = 9_000L
        private const val MAX_BYTES = 32 * 1024
        private const val HOPS = 3
        private const val BUFFER_BYTES = 2_048
        private const val MAX_LOCATION = 2_048
        private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "regional-deadline").apply { isDaemon = true }
        }

    }

    fun check(service: RegionalService, cancellation: ProbeCancellation): RegionalHttpResponse {
        val deadline = deadlines.schedule({ cancellation.close() }, BUDGET_MS, TimeUnit.MILLISECONDS)
        val measured = try { request(service, cancellation) }
        catch (_: UnknownHostException) { failure(RegionalFailure.DNS) }
        catch (_: SSLException) { failure(RegionalFailure.TLS) }
        catch (_: SocketTimeoutException) { failure(RegionalFailure.TIMEOUT) }
        catch (_: InterruptedException) { failure(RegionalFailure.TIMEOUT) }
        catch (_: IOException) { failure(RegionalFailure.CONNECTION) }
        catch (_: IllegalArgumentException) { failure(RegionalFailure.UNMEASURED) }
        finally { deadline.cancel(false) }
        return measured
    }

    private fun failure(failure: RegionalFailure) =
        RegionalHttpResponse(RegionalStatus.NETWORK_ERROR, failure = failure)

    private fun request(service: RegionalService,
        cancellation: ProbeCancellation): RegionalHttpResponse {
        var uri = URI(service.address)
        repeat(HOPS) { hop ->
            val step = exchange(service, cancellation, uri, hop)
            if (step.result != null) return step.result
            uri = checkNotNull(step.next)
        }
        return RegionalHttpResponse(RegionalStatus.UNKNOWN)
    }

    private data class Step(val next: URI? = null, val result: RegionalHttpResponse? = null)

    private fun exchange(service: RegionalService, cancellation: ProbeCancellation,
        uri: URI, hop: Int): Step {
        cancellation.check()
        val connection = openConnection(uri)
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
            return if (code in RegionalHttpStatus.redirect) redirect(service, connection, uri, hop, code)
            else Step(result = body(connection, code, cancellation))
        } finally { connection.disconnect() }
    }

    private fun redirect(service: RegionalService, connection: HttpsURLConnection,
        uri: URI, hop: Int, code: Int): Step {
        val location = connection.getHeaderField("Location")?.takeIf { it.length <= MAX_LOCATION }
        val next = location?.let { runCatching { uri.resolve(it) }.getOrNull() }
        val status = RegionalClassifier.classify(code, "", "", next?.host.orEmpty())
        return when {
            status == RegionalStatus.AUTH_REQUIRED -> Step(result = RegionalHttpResponse(status, code))
            hop < HOPS - 1 && next != null && allowed(service, next) -> Step(next = next)
            else -> Step(result = RegionalHttpResponse(RegionalStatus.UNKNOWN, code))
        }
    }

    private fun body(connection: HttpsURLConnection, code: Int, cancellation: ProbeCancellation): RegionalHttpResponse {
        val input = if (code >= RegionalHttpStatus.denial.first) connection.errorStream else connection.inputStream
        val bytes = ByteArrayOutputStream()
        input?.use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            var count = stream.read(buffer)
            while (count >= 0 && bytes.size() <= MAX_BYTES) {
                cancellation.check()
                if (count == 0) throw IOException("Empty transport read")
                bytes.write(buffer, 0, count)
                count = if (bytes.size() <= MAX_BYTES)
                    stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - bytes.size())) else -1
            }
        }
        cancellation.check()
        return RegionalHttpResponse(RegionalClassifier.classify(code, connection.contentType.orEmpty(),
            bytes.toByteArray().toString(Charsets.UTF_8), complete = bytes.size() <= MAX_BYTES), code)
    }

    private fun allowed(service: RegionalService, uri: URI): Boolean = uri.scheme == "https" &&
        uri.host in service.canonicalHosts && uri.userInfo == null && uri.port in setOf(-1, RegionalHttpStatus.TLS_PORT)
}

internal data class RegionalHttpResponse(val status: RegionalStatus, val code: Int? = null,
    val failure: RegionalFailure? = null)
