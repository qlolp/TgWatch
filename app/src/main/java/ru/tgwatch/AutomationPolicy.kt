package ru.tgwatch

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Small, Android-independent boundary for the bearer-token API. */
object AutomationPolicy {
    const val ACTION_CHECK_NOW = "ru.tgwatch.automation.CHECK_NOW"
    const val ACTION_GET_STATE = "ru.tgwatch.automation.GET_STATE"
    const val TOKEN_LENGTH = 64
    const val CHECK_GAP_MS = 10_000L

    fun validAction(action: String?): Boolean =
        action == ACTION_CHECK_NOW || action == ACTION_GET_STATE

    fun validToken(token: String?): Boolean = token != null && token.length == TOKEN_LENGTH &&
        token.all { it in '0'..'9' || it in 'a'..'f' }

    fun authorized(enabled: Boolean, expected: String?, supplied: String?): Boolean {
        if (!enabled || !validToken(expected) || !validToken(supplied)) return false
        // Both byte arrays have exactly 64 ASCII bytes; no secret-dependent early exit.
        return MessageDigest.isEqual(expected!!.toByteArray(StandardCharsets.US_ASCII),
            supplied!!.toByteArray(StandardCharsets.US_ASCII))
    }

    fun checkAllowed(elapsed: Long, lastElapsed: Long?): Boolean = elapsed >= 0L &&
        (lastElapsed == null ||
            (lastElapsed >= 0L && elapsed >= lastElapsed && elapsed - lastElapsed >= CHECK_GAP_MS))

    fun stale(running: Boolean, checkedAt: Long, now: Long, expectedIntervalSec: Int): Boolean =
        !running || ProbeRules.isStale(checkedAt, now, expectedIntervalSec)
}
