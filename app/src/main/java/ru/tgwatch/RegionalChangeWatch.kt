package ru.tgwatch

data class RegionalWatchRequest(val armedElapsed: Long, val deadlineElapsed: Long) {
    init {
        require(armedElapsed >= 0 && deadlineElapsed > armedElapsed &&
            deadlineElapsed - armedElapsed in 1L..TTL_MS)
    }
    fun active(now: Long): Boolean = now >= armedElapsed && now < deadlineElapsed
    fun afterReboot(nowElapsed: Long, armedWall: Long, deadlineWall: Long,
        nowWall: Long): RegionalWatchRequest? {
        if (nowElapsed < 0 || armedWall < 0 || deadlineWall <= armedWall ||
            deadlineWall - armedWall !in 1L..TTL_MS || nowWall < armedWall || nowWall >= deadlineWall) return null
        val remaining = deadlineWall - nowWall
        return runCatching { RegionalWatchRequest(nowElapsed, Math.addExact(nowElapsed, remaining)) }.getOrNull()
    }
    companion object { const val TTL_MS = 24 * 60 * 60_000L }
}

data class RegionalWatchMetadata(val request: RegionalWatchRequest, val boot: Int, val profileRevision: Long,
    val restoredAfterBoot: Boolean = false) {
    init { require(boot >= 0 && profileRevision >= 0) }
    fun current(currentBoot: Int, revision: Long, enabled: Boolean, now: Long): Boolean =
        currentBoot >= 0 && currentBoot == boot && profileRevision == revision && enabled && request.active(now)
}

/** Armed intent can survive process death. Positive observations deliberately cannot. */
internal class RegionalChangeWatch(val request: RegionalWatchRequest) {
    private var context: String? = null
    private var first: Long? = null
    private var lastPositive = -1L
    private var lastChecked = -1L
    private var lastNow = -1L
    private var consumed = false

    val confirmationDue: Long? get() = first?.takeIf {
        !consumed && lastPositive - it < CONFIRM_MS
    }?.plus(CONFIRM_MS)

    fun observe(status: RegionalStatus, scope: String?, checkedElapsed: Long, now: Long, maxAgeMs: Long) {
        val clock = checkClock(now)
        val fresh = checkedElapsed > request.armedElapsed && checkedElapsed <= now &&
            maxAgeMs >= 0 && now - checkedElapsed <= maxAgeMs
        val active = request.active(now) && !consumed && scope != null
        if (!clock || !fresh || !active) {
            resetEvidence()
            return
        }
        if (context != scope) { resetEvidence(); context = scope }
        if (checkedElapsed <= lastChecked) return
        lastChecked = checkedElapsed
        if (status == RegionalStatus.WEB_REACHABLE) {
            val previous = first
            if (previous == null || checkedElapsed - previous > maxAgeMs) first = checkedElapsed
            lastPositive = checkedElapsed
        } else { first = null; lastPositive = -1 }
    }

    fun ready(scope: String?, now: Long, maxAgeMs: Long): Boolean {
        val clock = checkClock(now)
        val began = first
        val same = scope != null && scope == context && !consumed && request.active(now)
        val pair = began != null && lastPositive - began >= CONFIRM_MS && now >= lastPositive
        val fresh = began != null && maxAgeMs >= 0 && now >= began && now - began <= maxAgeMs
        return clock && same && pair && fresh
    }

    fun resetEvidence() { context = null; first = null; lastPositive = -1; lastChecked = -1 }
    fun delivered() { consumed = true; resetEvidence() }

    private fun checkClock(now: Long): Boolean {
        val forward = now >= lastNow
        if (!forward) resetEvidence()
        lastNow = now
        return forward
    }

    companion object { const val CONFIRM_MS = 15_000L }
}
