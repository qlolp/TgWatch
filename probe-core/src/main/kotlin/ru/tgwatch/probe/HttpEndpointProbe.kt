package ru.tgwatch.probe

import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** HTTP semantics are explicit: general web reachability, exact2xx, or Matrix versions evidence. */
enum class HttpProbeMode { REACHABLE, STRICT_2XX, MATRIX_VERSIONS }
data class HttpProbeResult(val code: Int, val confirmed: Boolean)

class HttpEndpointProbe(private val transport: ProbeTransport) {
    fun check(endpoint: ProbeEndpoint, cancellation: ProbeCancellation): HttpProbeResult {
        if (endpoint.httpMode == HttpProbeMode.MATRIX_VERSIONS) return request(endpoint, "GET", cancellation)
        val head = request(endpoint, "HEAD", cancellation)
        return if (head.code == METHOD_NOT_ALLOWED || head.code == NOT_IMPLEMENTED)
            request(endpoint, "GET", cancellation) else head
    }

    private fun request(endpoint: ProbeEndpoint, method: String, cancellation: ProbeCancellation): HttpProbeResult {
        cancellation.check()
        val connection = transport.openConnection(URL(endpoint.address))
        cancellation.register { connection.disconnect() }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", transport.userAgent)
            if (endpoint.httpMode == HttpProbeMode.MATRIX_VERSIONS) {
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
            }
            val code = connection.responseCode
            val confirmed = when (endpoint.httpMode) {
                HttpProbeMode.REACHABLE -> ProbePolicy.isReachableHttpCode(code)
                HttpProbeMode.STRICT_2XX -> code in HTTP_OK..HTTP_LAST_SUCCESS
                HttpProbeMode.MATRIX_VERSIONS -> code in HTTP_OK..HTTP_LAST_SUCCESS &&
                    MatrixVersions.confirm(connection, cancellation)
            }
            HttpProbeResult(code, confirmed)
        } finally { connection.disconnect() }
    }

    private companion object {
        const val TIMEOUT_MS = 3_000
        const val HTTP_OK = 200
        const val HTTP_LAST_SUCCESS = 299
        const val METHOD_NOT_ALLOWED = 405
        const val NOT_IMPLEMENTED = 501
    }
}

private object MatrixVersions {
    private const val MAX_BYTES = 64 * 1024
    private const val BUFFER_BYTES = 2048
    private const val MAX_VERSION_CHARS = 128

    fun confirm(connection: HttpURLConnection, cancellation: ProbeCancellation): Boolean {
        val bytes = body(connection, cancellation) ?: return false
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val reader = StrictJsonReader(StringReader(text))
        var versions = 0
        reader.objectFields { key ->
            if (key == "versions") reader.arrayValues {
                require(reader.string(MAX_VERSION_CHARS).isNotBlank())
                versions++
            } else reader.skipValue()
        }
        reader.finish()
        return versions > 0
    }

    private fun body(connection: HttpURLConnection, cancellation: ProbeCancellation): ByteArray? {
        val length = connection.contentLengthLong
        if (length > MAX_BYTES) return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        connection.inputStream.use { input ->
            while (output.size() < MAX_BYTES) {
                cancellation.check()
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
        }
        // Unknown-length bodies reaching the cap are rejected without reading an extra byte.
        return output.toByteArray().takeIf {
            (it.size < MAX_BYTES || length == MAX_BYTES.toLong()) && (length < 0 || length == it.size.toLong())
        }
    }
}
