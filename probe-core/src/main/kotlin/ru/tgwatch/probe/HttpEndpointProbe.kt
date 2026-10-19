package ru.tgwatch.probe

import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException

/** HTTP semantics are explicit: general web reachability, exact2xx, or Matrix versions evidence. */
enum class HttpProbeMode { REACHABLE, STRICT_2XX, MATRIX_VERSIONS }
data class HttpProbeResult(val code: Int, val confirmed: Boolean, val failure: HttpConditionFailure? = null)

class HttpEndpointProbe(private val transport: ProbeTransport) {
    fun check(endpoint: ProbeEndpoint, cancellation: ProbeCancellation): HttpProbeResult {
        require(endpoint.condition == null || endpoint.httpMode == HttpProbeMode.STRICT_2XX)
        if (endpoint.httpMode == HttpProbeMode.MATRIX_VERSIONS || endpoint.condition?.jsonField != null)
            return request(endpoint, "GET", cancellation)
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
            if (endpoint.httpMode == HttpProbeMode.MATRIX_VERSIONS || endpoint.condition?.jsonField != null) {
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
            }
            val code = connection.responseCode
            endpoint.condition?.let { condition ->
                return CustomHttpHealth.confirm(connection, cancellation, condition, code)
            }
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
    private const val MAX_VERSION_CHARS = 128

    fun confirm(connection: HttpURLConnection, cancellation: ProbeCancellation): Boolean {
        val bytes = BoundedHttpBody.read(connection, cancellation, MAX_BYTES) ?: return false
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

}

private object CustomHttpHealth {
    private const val MAX_BYTES = 32 * 1024
    private const val MAX_VALUE_CHARS = 256
    fun confirm(connection: HttpURLConnection, cancellation: ProbeCancellation,
        condition: HttpProbeCondition, code: Int): HttpProbeResult {
        val codeMatches = condition.expectedCode?.let { it == code } ?: (code in 200..299)
        if (!codeMatches) return HttpProbeResult(code, false, HttpConditionFailure.CODE_MISMATCH)
        if (condition.jsonField == null) return HttpProbeResult(code, true)
        val encoding = connection.contentEncoding
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return unconfirmed(code)
        val bytes = BoundedHttpBody.read(connection, cancellation, MAX_BYTES,
            errorBody = code >= HttpURLConnection.HTTP_BAD_REQUEST) ?: return unconfirmed(code)
        val matches = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val reader = StrictJsonReader(StringReader(text))
            var found = false
            reader.objectFields { key ->
                if (key == condition.jsonField && reader.nextType == '"')
                    found = reader.string(MAX_VALUE_CHARS) == condition.expectedValue
                else reader.skipValue()
            }
            reader.finish()
            found
        } catch (_: IllegalArgumentException) { false }
        catch (_: CharacterCodingException) { false }
        return if (matches) HttpProbeResult(code, true) else unconfirmed(code)
    }
    private fun unconfirmed(code: Int) = HttpProbeResult(code, false, HttpConditionFailure.JSON_NOT_CONFIRMED)
}
