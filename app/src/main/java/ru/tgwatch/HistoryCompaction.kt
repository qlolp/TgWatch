package ru.tgwatch

/** Retention changes memory immediately; only repair debt requires an immediate durable replacement. */
class HistoryCompaction {
    private var repairDebt = false
    private var lastCompacted: Long? = null
    private var appends = 0
    private var prunedAt: Long? = null
    var retentionPending = false
        private set

    fun onPruned(nowMs: Long? = null) {
        retentionPending = true
        if (nowMs != null) prunedAt = maxOf(prunedAt ?: nowMs, nowMs)
    }
    fun failedWrite() { repairDebt = true }
    fun savedAppend() { if (appends < MAX_APPENDS) appends++ }
    fun savedReplacement(nowMs: Long) {
        lastCompacted = nowMs
        appends = 0
        repairDebt = false
        retentionPending = false
        prunedAt = null
    }

    fun needsReplacement(nowMs: Long, diskRows: Int, diskBytes: Long): Boolean {
        val previous = lastCompacted ?: return true
        return (prunedAt?.let { nowMs < it } ?: false) || repairDebt || appends >= MAX_APPENDS || nowMs < previous ||
            nowMs - previous >= INTERVAL_MS || diskRows > MAX_DISK_ROWS || diskBytes > MAX_DISK_BYTES
    }

    companion object {
        const val MAX_APPENDS = 1_024
        const val MAX_DISK_ROWS = 66_024
        const val MAX_DISK_BYTES = 16 * 1024 * 1024L
        private const val INTERVAL_MS = 6 * 60 * 60_000L
    }
}
