package ru.tgwatch.wear

/** A local watch measurement. Phone state and credentials are never inputs. */
data class WatchSnapshot(val status: String, val checkedAtMs: Long, val checkedElapsedMs: Long,
    val latencyMs: Long?, val revision: Long, val bootSession: Long) {
    init {
        require(status in STATUSES && checkedAtMs >= 0 && checkedElapsedMs >= 0 && bootSession >= 0)
        require(revision >= 0 && (latencyMs == null || latencyMs >= 0))
    }
    fun stale(nowElapsed: Long, currentBoot: Long): Boolean = checkedAtMs == 0L || checkedElapsedMs == 0L ||
        currentBoot != bootSession || nowElapsed < checkedElapsedMs || nowElapsed - checkedElapsedMs > STALE_MS
    companion object {
        val STATUSES = setOf("OK", "PARTIAL", "NO_NETWORK", "UNKNOWN")
        const val STALE_MS = 180_000L
    }
}
