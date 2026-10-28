package ru.tgwatch.probe

import java.util.Collections
import java.util.Locale

/**
 * Private health criteria. Values never belong in diagnostics or public payloads.
 *
 * [jsonField] and [expectedValue] retain the original single-condition API.
 * Additional JSON conditions and headers are also private health configuration.
 */
class HttpProbeCondition(val expectedCode: Int? = null, val jsonField: String? = null,
    val expectedValue: String? = null, val bodySubstring: String? = null,
    jsonConditions: List<HttpJsonCondition> = emptyList(),
    headers: List<HttpRequestHeader> = emptyList()) {
    val jsonConditions: List<HttpJsonCondition> = Collections.unmodifiableList(jsonConditions.toList())
    val headers: List<HttpRequestHeader> = Collections.unmodifiableList(headers.toList())

    init {
        require(expectedCode == null || expectedCode in FIRST_CODE..LAST_CODE) { "Invalid HTTP condition" }
        require((jsonField == null) == (expectedValue == null)) { "Invalid HTTP condition" }
        require(jsonField == null || validPath(jsonField)) { "Invalid HTTP condition" }
        require(expectedValue == null || expectedValue.length <= MAX_VALUE_CHARS) { "Invalid HTTP condition" }
        require((this.jsonConditions.size + (if (jsonField == null) 0 else 1)) in 0..MAX_JSON_CONDITIONS) {
            "Invalid HTTP condition"
        }
        this.jsonConditions.forEach { require(validPath(it.field)) { "Invalid HTTP condition" } }
        require(bodySubstring == null || bodySubstring.length <= MAX_BODY_SUBSTRING_CHARS) {
            "Invalid HTTP condition"
        }
        require(this.headers.size <= MAX_HEADERS &&
            this.headers.map { it.name.lowercase(Locale.ROOT) }.toSet().size == this.headers.size) {
            "Invalid HTTP condition"
        }
        this.headers.forEach { header ->
            require(validHeaderName(header.name) && header.value.length <= MAX_HEADER_VALUE_CHARS &&
                header.value.all { it in ' '..'~' } &&
                (!header.name.equals("Accept-Encoding", ignoreCase = true) ||
                    header.value.equals("identity", ignoreCase = true))) { "Invalid HTTP condition" }
        }
    }
    override fun toString() = "HttpProbeCondition(redacted)"

    val hasBodyCriteria: Boolean get() = bodySubstring != null || hasJsonCriteria
    val hasJsonCriteria: Boolean get() = jsonField != null || jsonConditions.isNotEmpty()

    /** Retains the original single-path API for existing clients of the probe model. */
    fun pathSegments(): List<String> = jsonField!!.split('.')

    fun allJsonConditions(): List<HttpJsonCondition> = buildList {
        if (jsonField != null) add(HttpJsonCondition(jsonField, checkNotNull(expectedValue)))
        addAll(jsonConditions)
    }

    fun copy(expectedCode: Int? = this.expectedCode, jsonField: String? = this.jsonField,
        expectedValue: String? = this.expectedValue, bodySubstring: String? = this.bodySubstring,
        jsonConditions: List<HttpJsonCondition> = this.jsonConditions,
        headers: List<HttpRequestHeader> = this.headers) =
        HttpProbeCondition(expectedCode, jsonField, expectedValue, bodySubstring, jsonConditions, headers)

    operator fun component1() = expectedCode
    operator fun component2() = jsonField
    operator fun component3() = expectedValue
    operator fun component4() = bodySubstring
    operator fun component5() = jsonConditions
    operator fun component6() = headers

    override fun equals(other: Any?): Boolean = other is HttpProbeCondition &&
        expectedCode == other.expectedCode && jsonField == other.jsonField &&
        expectedValue == other.expectedValue && bodySubstring == other.bodySubstring &&
        jsonConditions == other.jsonConditions && headers == other.headers

    override fun hashCode(): Int {
        var result = expectedCode ?: 0
        result = 31 * result + (jsonField?.hashCode() ?: 0)
        result = 31 * result + (expectedValue?.hashCode() ?: 0)
        result = 31 * result + (bodySubstring?.hashCode() ?: 0)
        result = 31 * result + jsonConditions.hashCode()
        result = 31 * result + headers.hashCode()
        return result
    }

    private companion object {
        const val FIRST_CODE = 200
        const val LAST_CODE = 599
        const val MAX_FIELD_CHARS = 64
        const val MAX_VALUE_CHARS = 256
        const val MAX_PATH_SEGMENTS = 3
        const val MAX_BODY_SUBSTRING_CHARS = 256
        const val MAX_JSON_CONDITIONS = 4
        const val MAX_HEADERS = 8
        const val MAX_HEADER_NAME_CHARS = 64
        const val MAX_HEADER_VALUE_CHARS = 256

        fun validPath(field: String): Boolean {
            if (field.isEmpty() || field.length > MAX_FIELD_CHARS) return false
            val parts = field.split('.')
            return parts.size in 1..MAX_PATH_SEGMENTS && parts.all { it.isNotEmpty() }
        }

        fun validHeaderName(name: String): Boolean = name.isNotEmpty() &&
            name.length <= MAX_HEADER_NAME_CHARS && name.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
            }
    }
}

data class HttpJsonCondition(val field: String, val expectedValue: String) {
    init {
        require(field.isNotEmpty() && field.length <= MAX_FIELD_CHARS &&
            field.split('.').let { it.size in 1..MAX_PATH_SEGMENTS && it.all(String::isNotEmpty) } &&
            expectedValue.length <= MAX_VALUE_CHARS) { "Invalid HTTP condition" }
    }
    override fun toString() = "HttpJsonCondition(redacted)"
    fun pathSegments(): List<String> = field.split('.')

    private companion object {
        const val MAX_FIELD_CHARS = 64
        const val MAX_VALUE_CHARS = 256
        const val MAX_PATH_SEGMENTS = 3
    }
}

data class HttpRequestHeader(val name: String, val value: String) {
    init {
        require(name.isNotEmpty() && name.length <= 64 && name.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
        } && value.length <= 256 && value.all { it in ' '..'~' }) {
            "Invalid HTTP condition"
        }
    }
    override fun toString() = "HttpRequestHeader(redacted)"
}

enum class HttpConditionFailure { CODE_MISMATCH, JSON_NOT_CONFIRMED }
