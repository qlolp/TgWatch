package ru.tgwatch

/** Independent observations on one network/configuration; delivery is acknowledged separately. */
class RegionalConsensus {
    private var scope: String? = null
    private var lastElapsed = -1L
    private var firstRegion = -1L
    private var restricted = false
    private var delivered = false
    private var lastNotification = -1L

    fun update(status: RegionalStatus, scope: String, elapsed: Long) {
        if (elapsed < lastElapsed || scope != this.scope) {
            firstRegion = -1
            restricted = false
            delivered = false
            if (elapsed < lastElapsed) lastNotification = -1
        }
        this.scope = scope
        lastElapsed = elapsed
        if (status == RegionalStatus.REGION_RESTRICTED) {
            if (firstRegion < 0) firstRegion = elapsed
            restricted = elapsed - firstRegion >= CONFIRM_MS
        } else {
            firstRegion = -1
            restricted = false
            if (status == RegionalStatus.WEB_REACHABLE) delivered = false
        }
    }

    fun confirmed(): Boolean = restricted

    fun shouldNotify(now: Long): Boolean = restricted && !delivered &&
        (lastNotification < 0 || now - lastNotification >= COOLDOWN_MS)

    fun recordNotification(now: Long) {
        delivered = true
        lastNotification = now
    }

    companion object {
        const val CONFIRM_MS = 15_000L
        const val COOLDOWN_MS = 300_000L
    }
}
