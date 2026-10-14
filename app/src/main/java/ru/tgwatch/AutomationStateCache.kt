package ru.tgwatch

import android.os.Bundle

/** Bounded automation snapshot used by GET_STATE and ordered CHECK_NOW replies. */
internal data class AutomationStateSnapshot(
    val status: String,
    val checkedAtMs: Long,
    val stale: Boolean,
    val running: Boolean,
    val apiVersion: Int = 1,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("status", status)
        putLong("checked_at_ms", checkedAtMs)
        putBoolean("stale", stale)
        putBoolean("running", running)
        putInt("api_version", apiVersion)
    }
}

/** Process-local GET_STATE snapshot. Avoids repeated prefs/binder work under polling. */
internal object AutomationStateCache {
    private var snapshot: AutomationStateSnapshot? = null
    private var cachedAtElapsed = -1L
    private var lastComputeElapsed = -1L
    private var computing = false

    @Synchronized fun take(elapsed: Long): AutomationStateSnapshot? {
        val value = snapshot
        return if (value != null && AutomationPolicy.stateCacheFresh(elapsed, cachedAtElapsed)) value else null
    }

    /** Reserve a compute slot. False when a fresh cache exists, another compute is running, or the gap is open. */
    @Synchronized fun beginCompute(elapsed: Long): Boolean {
        val canStart = take(elapsed) == null && !computing &&
            AutomationPolicy.stateComputeAllowed(elapsed, lastComputeElapsed.takeIf { it >= 0L })
        if (canStart) {
            computing = true
            lastComputeElapsed = elapsed
        }
        return canStart
    }

    @Synchronized fun endCompute(elapsed: Long, value: AutomationStateSnapshot?) {
        computing = false
        if (value != null) {
            snapshot = value
            cachedAtElapsed = elapsed
            lastComputeElapsed = elapsed
        }
    }

    @Synchronized fun clear() {
        snapshot = null
        cachedAtElapsed = -1L
        lastComputeElapsed = -1L
        computing = false
    }
}
