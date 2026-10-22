package ru.tgwatch.probe

/**
 * Private health criteria. Values never belong in diagnostics or public payloads.
 *
 * [jsonField] is a dotted path of at most [MAX_PATH_SEGMENTS] object keys whose
 * final value must be a JSON string, for example `status` or `status.indicator`.
 */
data class HttpProbeCondition(val expectedCode: Int? = null, val jsonField: String? = null,
    val expectedValue: String? = null) {
    init {
        require(expectedCode == null || expectedCode in FIRST_CODE..LAST_CODE) { "Invalid HTTP condition" }
        require((jsonField == null) == (expectedValue == null)) { "Invalid HTTP condition" }
        require(jsonField == null || validPath(jsonField)) { "Invalid HTTP condition" }
        require(expectedValue == null || expectedValue.length <= MAX_VALUE_CHARS) { "Invalid HTTP condition" }
    }
    override fun toString() = "HttpProbeCondition(redacted)"

    /** Non-empty object keys joined by dots; the full path stays within [MAX_FIELD_CHARS]. */
    fun pathSegments(): List<String> = jsonField!!.split('.')

    private companion object {
        const val FIRST_CODE = 200
        const val LAST_CODE = 599
        const val MAX_FIELD_CHARS = 64
        const val MAX_VALUE_CHARS = 256
        const val MAX_PATH_SEGMENTS = 3

        fun validPath(field: String): Boolean {
            if (field.isEmpty() || field.length > MAX_FIELD_CHARS) return false
            val parts = field.split('.')
            return parts.size in 1..MAX_PATH_SEGMENTS && parts.all { it.isNotEmpty() }
        }
    }
}

enum class HttpConditionFailure { CODE_MISMATCH, JSON_NOT_CONFIRMED }
