package ru.tgwatch

enum class RegionalStatus {
    WEB_REACHABLE, REGION_RESTRICTED, AUTH_REQUIRED, CHALLENGE, RATE_LIMITED, NETWORK_ERROR, UNKNOWN
}

enum class RegionalFailure { DNS, TLS, TIMEOUT, CONNECTION, UNMEASURED }

/** No response contents, URLs, IPs, account data or exception messages cross this boundary. */
data class RegionalResult(val service: RegionalService, val status: RegionalStatus,
    val checkedAt: Long, val checkedElapsed: Long, val networkHandle: Long,
    val networkRevision: Long, val configurationRevision: Long, val httpCode: Int? = null,
    val failure: RegionalFailure? = null)
