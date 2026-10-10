package ru.tgwatch

/** One shared cooldown for failure alerts; UNKNOWN is never treated as a confirmed failure. */
object AlertRules {
    const val GAP_MS = 5 * 60 * 1000L

    fun shouldAlarm(status: String, tgDown: Boolean, offline: Boolean, partial: Boolean,
        allowed: Boolean, nowElapsedMs: Long, lastElapsedMs: Long): Boolean {
        val enabled = when (status) {
            "TG_DOWN" -> tgDown
            "NO_NETWORK" -> offline
            "PARTIAL" -> partial
            else -> false
        }
        return enabled && allowed && (lastElapsedMs < 0 || nowElapsedMs - lastElapsedMs >= GAP_MS)
    }

    fun recovered(previous: String, current: String, partialEnabled: Boolean): Boolean =
        current == "OK" && (previous == "TG_DOWN" || previous == "NO_NETWORK" ||
            (previous == "PARTIAL" && partialEnabled))
}
