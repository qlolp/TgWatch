package ru.tgwatch

import android.os.Bundle

/** Bounded automation snapshot used by GET_STATE and ordered CHECK_NOW replies. */
internal data class AutomationStateSnapshot(
    val status: String,
    val checkedAtMs: Long,
    val stale: Boolean,
    val running: Boolean,
    val apiVersion: Int = 1,
    val targetId: TargetId? = null,
    val latencyMs: Long? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("status", status)
        putLong("checked_at_ms", checkedAtMs)
        putBoolean("stale", stale)
        putBoolean("running", running)
        putInt("api_version", apiVersion)
        if (apiVersion == 2) {
            putString("target_id", checkNotNull(targetId).wire)
            latencyMs?.let { putLong("latency_ms", it) }
        }
    }
}

/** Process-local GET_STATE snapshot. Avoids repeated prefs/binder work under polling. */
internal object AutomationStateCache {
    private data class Entry(var snapshot: AutomationStateSnapshot? = null, var cachedAtElapsed: Long = -1L,
        var lastComputeElapsed: Long = -1L, var computing: Boolean = false)
    private val entries = mutableMapOf<AutomationStateKey, Entry>()

    @Synchronized fun take(elapsed: Long, key: AutomationStateKey = AutomationStateKey()): AutomationStateSnapshot? {
        val entry = entries[key]
        val value = entry?.snapshot
        return if (value != null && AutomationPolicy.stateCacheFresh(elapsed, entry.cachedAtElapsed)) value else null
    }

    /** Reserve a compute slot. False when a fresh cache exists, another compute is running, or the gap is open. */
    @Synchronized fun beginCompute(elapsed: Long, key: AutomationStateKey = AutomationStateKey()): Boolean {
        val entry = entries.getOrPut(key, ::Entry)
        val canStart = take(elapsed, key) == null && !entry.computing &&
            AutomationPolicy.stateComputeAllowed(elapsed, entry.lastComputeElapsed.takeIf { it >= 0L })
        if (canStart) {
            entry.computing = true
            entry.lastComputeElapsed = elapsed
        }
        return canStart
    }

    @Synchronized fun endCompute(elapsed: Long, value: AutomationStateSnapshot?,
        key: AutomationStateKey = AutomationStateKey()) {
        val entry = entries.getOrPut(key, ::Entry)
        entry.computing = false
        if (value != null) {
            entry.snapshot = value
            entry.cachedAtElapsed = elapsed
            entry.lastComputeElapsed = elapsed
        }
    }

    @Synchronized fun clear() { entries.clear() }
}

/** Exactly five possible cache entries: legacy Telegram and the four version-two targets. */
internal data class AutomationStateKey(val targetId: TargetId = TargetId.TELEGRAM, val apiVersion: Int = 1) {
    init { require(apiVersion == 2 || apiVersion == 1 && targetId == TargetId.TELEGRAM) }
}
