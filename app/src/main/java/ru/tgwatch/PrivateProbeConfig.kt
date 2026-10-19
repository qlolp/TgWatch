package ru.tgwatch

import java.io.StringReader
import org.json.JSONObject
import ru.tgwatch.probe.HttpProbeCondition
import ru.tgwatch.probe.StrictJsonReader

/** One immutable CP-only value; never persist this in public target definitions. */
internal data class PrivateProbeConfig(val url: String, val condition: HttpProbeCondition? = null) {
    init {
        require(HttpsUrlPolicy.normalize(url, MAX_URL_CHARS, false) == url) { "Invalid private probe configuration" }
    }
    override fun toString() = "PrivateProbeConfig(redacted)"
    companion object { const val MAX_URL_CHARS = 256 }
}

internal object PrivateProbeConfigCodec {
    const val MAX_BYTES = 4096
    private const val VERSION = 1L
    private const val FIRST_CODE = 200L
    private const val LAST_CODE = 599L
    private const val MAX_FIELD_CHARS = 64
    private const val MAX_VALUE_CHARS = 256
    fun encode(value: PrivateProbeConfig): String = JSONObject().put("version", VERSION).put("url", value.url)
        .apply { value.condition?.let { condition ->
            condition.expectedCode?.let { put("expected_code", it) }
            condition.jsonField?.let { put("json_field", it); put("expected_value", condition.expectedValue) }
        } }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }

    fun decode(raw: String): PrivateProbeConfig = try {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val legacy = HttpsUrlPolicy.normalize(raw, PrivateProbeConfig.MAX_URL_CHARS, false)
        if (legacy != null) PrivateProbeConfig(legacy) else envelope(raw)
    } catch (_: IllegalArgumentException) {
        // No input/cause: parser errors and URLs may contain credentials.
        throw IllegalArgumentException("Invalid private probe configuration")
    }

    private fun envelope(raw: String): PrivateProbeConfig {
        val reader = StrictJsonReader(StringReader(raw))
        var version: Long? = null
        var url: String? = null
        var code: Int? = null
        var field: String? = null
        var value: String? = null
        reader.objectFields { key -> when (key) {
            "version" -> version = reader.longValue()
            "url" -> url = reader.string(PrivateProbeConfig.MAX_URL_CHARS)
            "expected_code" -> code = reader.longValue().also { require(it in FIRST_CODE..LAST_CODE) }.toInt()
            "json_field" -> field = reader.string(MAX_FIELD_CHARS)
            "expected_value" -> value = reader.string(MAX_VALUE_CHARS)
            else -> throw IllegalArgumentException("Invalid private probe configuration")
        } }
        reader.finish()
        require(version == VERSION && url != null)
        val condition = if (code != null || field != null || value != null)
            HttpProbeCondition(code, field, value) else null
        return PrivateProbeConfig(checkNotNull(url), condition)
    }
}
