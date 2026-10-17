package ru.tgwatch

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Small, Android-independent boundary for the bearer-token API. */
object AutomationPolicy {
    const val ACTION_CHECK_NOW = "ru.tgwatch.automation.CHECK_NOW"
    const val ACTION_GET_STATE = "ru.tgwatch.automation.GET_STATE"
    const val TOKEN_LENGTH = 64
    const val CHECK_GAP_MS = 10_000L
    /** Serve a previously built snapshot without prefs/binder work. */
    const val STATE_CACHE_MS = 1_000L
    /** Minimum gap between fresh snapshot computations when the cache has expired. */
    const val STATE_GAP_MS = 200L

    fun validAction(action: String?): Boolean =
        action == ACTION_CHECK_NOW || action == ACTION_GET_STATE ||
            action == TargetAutomation.ACTION_GET_TARGET_STATE || action == TargetAutomation.ACTION_CHECK_TARGET_NOW

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

    fun stateCacheFresh(elapsed: Long, cachedAtElapsed: Long?): Boolean =
        cachedAtElapsed != null && cachedAtElapsed >= 0L && elapsed >= cachedAtElapsed &&
            elapsed - cachedAtElapsed < STATE_CACHE_MS

    fun stateComputeAllowed(elapsed: Long, lastComputeElapsed: Long?): Boolean = elapsed >= 0L &&
        (lastComputeElapsed == null ||
            (lastComputeElapsed >= 0L && elapsed >= lastComputeElapsed &&
                elapsed - lastComputeElapsed >= STATE_GAP_MS))

    fun stale(running: Boolean, checkedAt: Long, now: Long, expectedIntervalSec: Int): Boolean =
        !running || ProbeRules.isStale(checkedAt, now, expectedIntervalSec)
}
