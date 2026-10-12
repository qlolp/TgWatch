package ru.tgwatch

/** "Mute for 1 hour" from an alert: a wall-clock deadline, so it survives process death. */
object AlertMute {
    const val DURATION_MS = 60 * 60_000L

    fun until(now: Long): Long = now + DURATION_MS

    /** A deadline more than one period ahead means the clock went back; treat it as expired. */
    fun active(until: Long, now: Long): Boolean = until > now && until - now <= DURATION_MS
}
