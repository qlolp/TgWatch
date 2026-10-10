package ru.tgwatch

/** Network callbacks reset this immediately; Telegram failures retain their normal frequency. */
class OfflineBackoff {
    private var stage = 0
    @Synchronized fun reset() { stage = 0 }
    @Synchronized fun next(status: String, baseSec: Int): Int {
        if (status != "NO_NETWORK") { stage = 0; return baseSec }
        val delays = intArrayOf(30, 60, 120, 300)
        val delay = maxOf(baseSec, delays[stage])
        stage = (stage + 1).coerceAtMost(delays.lastIndex)
        return delay.coerceAtMost(300)
    }
}
