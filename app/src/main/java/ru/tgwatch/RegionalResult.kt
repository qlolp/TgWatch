package ru.tgwatch

internal object RegionalHttpStatus {
    private const val SUCCESS_FIRST = 200
    private const val SUCCESS_LAST = 299
    private const val REDIRECT_FIRST = 300
    private const val REDIRECT_LAST = 399
    private const val DENIAL_FIRST = 400
    private const val DENIAL_LAST = 499
    val success = SUCCESS_FIRST..SUCCESS_LAST
    val redirect = REDIRECT_FIRST..REDIRECT_LAST
    val denial = DENIAL_FIRST..DENIAL_LAST
    const val AUTH_REQUIRED = 401
    const val RATE_LIMITED = 429
    const val TLS_PORT = 443
}

enum class RegionalStatus {
    WEB_REACHABLE, REGION_RESTRICTED, AUTH_REQUIRED, CHALLENGE, RATE_LIMITED, NETWORK_ERROR, UNKNOWN
}

enum class RegionalFailure { DNS, TLS, TIMEOUT, CONNECTION, UNMEASURED }

/** No response contents, URLs, IPs, account data or exception messages cross this boundary. */
data class RegionalResult(val service: RegionalService, val status: RegionalStatus,
    val checkedAt: Long, val checkedElapsed: Long, val networkHandle: Long,
    val networkRevision: Long, val configurationRevision: Long, val httpCode: Int? = null,
    val failure: RegionalFailure? = null, val regionConfirmed: Boolean = false)
