package ru.tgwatch

/**
 * Classifies an HTTPS response as a possible regional block.
 *
 * Redirects are not followed: only the first status, Location and a bounded body
 * snippet are considered. Response text is never logged or stored.
 */
object GeoBlockPolicy {
    const val MAX_BODY_BYTES = 8 * 1024

    enum class Outcome { OK, GEO_BLOCK, DOWN, UNKNOWN }

    private val BODY_PHRASES = listOf(
        "not available in your country",
        "isn't available in your country",
        "is not available in your country",
        "unavailable in your country",
        "unsupported_country",
        "unsupported country",
    )

    private val LOCATION_TOKENS = listOf(
        "unsupported", "unavailable", "country", "region", "geoblock", "geo-block",
    )

    fun classify(statusCode: Int, location: String?, bodySnippet: String?): Outcome = when {
        statusCode == HTTP_UNAVAILABLE_FOR_LEGAL || statusCode == HTTP_FORBIDDEN -> Outcome.GEO_BLOCK
        statusCode in HTTP_OK..HTTP_LAST_SUCCESS ->
            if (bodyLooksBlocked(bodySnippet)) Outcome.GEO_BLOCK else Outcome.OK
        statusCode in HTTP_REDIRECT_FIRST..HTTP_REDIRECT_LAST ->
            if (locationLooksBlocked(location)) Outcome.GEO_BLOCK else Outcome.OK
        statusCode in HTTP_SERVER_ERROR_FIRST..HTTP_SERVER_ERROR_LAST -> Outcome.DOWN
        statusCode in HTTP_CLIENT_ERROR_FIRST..HTTP_CLIENT_ERROR_LAST -> Outcome.UNKNOWN
        statusCode <= 0 -> Outcome.DOWN
        else -> Outcome.UNKNOWN
    }

    fun bodyLooksBlocked(bodySnippet: String?): Boolean {
        if (bodySnippet.isNullOrEmpty()) return false
        val lower = bodySnippet.lowercase()
        return BODY_PHRASES.any { lower.contains(it) }
    }

    fun locationLooksBlocked(location: String?): Boolean {
        if (location.isNullOrBlank()) return false
        val lower = location.lowercase()
        return LOCATION_TOKENS.any { lower.contains(it) }
    }

    private const val HTTP_OK = 200
    private const val HTTP_LAST_SUCCESS = 299
    private const val HTTP_REDIRECT_FIRST = 300
    private const val HTTP_REDIRECT_LAST = 399
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_UNAVAILABLE_FOR_LEGAL = 451
    private const val HTTP_CLIENT_ERROR_FIRST = 400
    private const val HTTP_CLIENT_ERROR_LAST = 499
    private const val HTTP_SERVER_ERROR_FIRST = 500
    private const val HTTP_SERVER_ERROR_LAST = 599
}
