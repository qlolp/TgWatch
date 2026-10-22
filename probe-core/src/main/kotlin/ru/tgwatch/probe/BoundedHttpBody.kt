package ru.tgwatch.probe

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

/** Never reads beyond cap, including unknown-length streams. Cancellation owns disconnect. */
internal object BoundedHttpBody {
    private const val BUFFER_BYTES = 2048
    fun read(connection: HttpURLConnection, cancellation: ProbeCancellation, cap: Int,
        errorBody: Boolean = false): ByteArray? {
        val length = connection.contentLengthLong
        val source = if (errorBody) connection.errorStream else connection.inputStream
        val body = if (length > cap || source == null) null
            else source.use { input -> readBounded(input, cancellation, cap) }
        return body?.takeIf {
            (it.size < cap || length == cap.toLong()) && (length < 0 || length == it.size.toLong())
        }
    }

    private fun readBounded(input: java.io.InputStream, cancellation: ProbeCancellation, cap: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (output.size() < cap) {
            cancellation.check()
            val count = input.read(buffer, 0, minOf(buffer.size, cap - output.size()))
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
