package ru.tgwatch.probe

/** Private health criteria. Values never belong in diagnostics or public payloads. */
data class HttpProbeCondition(val expectedCode: Int? = null, val jsonField: String? = null,
    val expectedValue: String? = null) {
    init {
        require(expectedCode == null || expectedCode in FIRST_CODE..LAST_CODE) { "Invalid HTTP condition" }
        require((jsonField == null) == (expectedValue == null)) { "Invalid HTTP condition" }
        require(jsonField == null || jsonField.length in 1..MAX_FIELD_CHARS) { "Invalid HTTP condition" }
        require(expectedValue == null || expectedValue.length <= MAX_VALUE_CHARS) { "Invalid HTTP condition" }
    }
    override fun toString() = "HttpProbeCondition(redacted)"
    private companion object {
        const val FIRST_CODE = 200
        const val LAST_CODE = 599
        const val MAX_FIELD_CHARS = 64
        const val MAX_VALUE_CHARS = 256
    }
}

enum class HttpConditionFailure { CODE_MISMATCH, JSON_NOT_CONFIRMED }
