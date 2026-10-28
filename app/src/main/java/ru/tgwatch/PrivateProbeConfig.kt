package ru.tgwatch

import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import ru.tgwatch.probe.HttpProbeCondition
import ru.tgwatch.probe.HttpJsonCondition
import ru.tgwatch.probe.HttpRequestHeader
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
    const val MAX_BYTES = 16 * 1024
    private const val VERSION = 2L
    private const val LEGACY_VERSION = 1L
    private const val FIRST_CODE = 200L
    private const val LAST_CODE = 599L
    private const val MAX_FIELD_CHARS = 64
    private const val MAX_VALUE_CHARS = 256
    private const val MAX_HEADER_NAME_CHARS = 64
    private const val MAX_HEADER_VALUE_CHARS = 256
    private const val MAX_JSON_CONDITIONS = 4
    private const val MAX_HEADERS = 8
    fun encode(value: PrivateProbeConfig): String = JSONObject().put("version", VERSION).put("url", value.url)
        .apply { value.condition?.let { condition ->
            condition.expectedCode?.let { put("expected_code", it) }
            condition.jsonField?.let { put("json_field", it); put("expected_value", condition.expectedValue) }
            condition.bodySubstring?.let { put("body_substring", it) }
            if (condition.jsonConditions.isNotEmpty()) put("json_conditions", JSONArray().apply {
                condition.jsonConditions.forEach {
                    put(JSONObject().put("field", it.field).put("value", it.expectedValue))
                }
            })
            if (condition.headers.isNotEmpty()) put("headers", JSONArray().apply {
                condition.headers.forEach { put(JSONObject().put("name", it.name).put("value", it.value)) }
            })
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
        val parsed = ParsedEnvelope()
        reader.objectFields { key -> readEnvelopeField(reader, key, parsed) }
        reader.finish()
        return parsed.build()
    }

    private class ParsedEnvelope {
        var version: Long? = null
        var url: String? = null
        var code: Int? = null
        var field: String? = null
        var value: String? = null
        var bodySubstring: String? = null
        var jsonConditions = emptyList<HttpJsonCondition>()
        var headers = emptyList<HttpRequestHeader>()
        var hasNewFields = false

        fun build(): PrivateProbeConfig {
            require(version in setOf(LEGACY_VERSION, VERSION) && url != null)
            require(version != LEGACY_VERSION || !hasNewFields)
            val condition = if (code != null || field != null || value != null)
                HttpProbeCondition(code, field, value, bodySubstring, jsonConditions, headers)
            else if (bodySubstring != null || jsonConditions.isNotEmpty() || headers.isNotEmpty())
                HttpProbeCondition(bodySubstring = bodySubstring, jsonConditions = jsonConditions, headers = headers)
            else null
            return PrivateProbeConfig(requireNotNull(url), condition)
        }
    }

    private fun readEnvelopeField(reader: StrictJsonReader, key: String, parsed: ParsedEnvelope) {
        when (key) {
            "version" -> parsed.version = reader.longValue()
            "url" -> parsed.url = reader.string(PrivateProbeConfig.MAX_URL_CHARS)
            "expected_code" -> parsed.code = reader.longValue()
                .also { require(it in FIRST_CODE..LAST_CODE) }.toInt()
            "json_field" -> parsed.field = reader.string(MAX_FIELD_CHARS)
            "expected_value" -> parsed.value = reader.string(MAX_VALUE_CHARS)
            "body_substring" -> {
                parsed.hasNewFields = true
                parsed.bodySubstring = reader.string(MAX_VALUE_CHARS)
            }
            "json_conditions" -> {
                parsed.hasNewFields = true
                parsed.jsonConditions = readJsonConditions(reader)
            }
            "headers" -> {
                parsed.hasNewFields = true
                parsed.headers = readHeaders(reader)
            }
            else -> invalid()
        }
    }

    private fun readJsonConditions(reader: StrictJsonReader): List<HttpJsonCondition> =
        mutableListOf<HttpJsonCondition>().also { parsed ->
            reader.arrayValues { index ->
                require(index < MAX_JSON_CONDITIONS)
                val fields = mutableMapOf<String, String>()
                reader.objectFields { key ->
                    val limit = when (key) {
                        "field" -> MAX_FIELD_CHARS
                        "value" -> MAX_VALUE_CHARS
                        else -> invalid()
                    }
                    fields[key] = reader.string(limit)
                }
                parsed += HttpJsonCondition(requireNotNull(fields["field"]), requireNotNull(fields["value"]))
            }
        }

    private fun readHeaders(reader: StrictJsonReader): List<HttpRequestHeader> =
        mutableListOf<HttpRequestHeader>().also { parsed ->
            reader.arrayValues { index ->
                require(index < MAX_HEADERS)
                val fields = mutableMapOf<String, String>()
                reader.objectFields { key ->
                    val limit = when (key) {
                        "name" -> MAX_HEADER_NAME_CHARS
                        "value" -> MAX_HEADER_VALUE_CHARS
                        else -> invalid()
                    }
                    fields[key] = reader.string(limit)
                }
                parsed += HttpRequestHeader(requireNotNull(fields["name"]), requireNotNull(fields["value"]))
            }
        }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid private probe configuration")
}
