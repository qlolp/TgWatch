package ru.tgwatch

/** A device-local timer survives process death, but never a device reboot. */
object AlertMute {
    const val DURATION_MS = 60 * 60_000L

    fun until(now: Long): Long = now + DURATION_MS

    /** A deadline more than one period ahead means the clock went back; treat it as expired. */
    fun active(until: Long, now: Long): Boolean = until > now && until - now in 1L..DURATION_MS

    data class State(
        val bootCount: Int? = null,
        val startedElapsed: Long = 0L,
        val untilElapsed: Long = 0L,
        val legacyUntil: Long = 0L,
    )

    interface Store {
        fun read(): State
        /** Returns true only after the state has been durably written. */
        fun write(state: State): Boolean
    }

    fun mute(store: Store, elapsed: Long, bootCount: Int?): Boolean {
        val valid = bootCount != null && bootCount >= 0 && elapsed >= 0 && elapsed <= Long.MAX_VALUE - DURATION_MS
        val state = if (valid) State(bootCount, elapsed, elapsed + DURATION_MS) else State()
        val saved = store.write(state)
        // SharedPreferences may change its memory map even when commit reports a failed disk write.
        if (!saved) clear(store)
        return valid && saved
    }

    fun clear(store: Store): Boolean = store.write(State())

    fun remaining(store: Store, wall: Long, elapsed: Long, bootCount: Int?): Long {
        val state = store.read()
        return when {
            bootCount == null || bootCount < 0 || elapsed < 0 -> discard(store, state)
            state.bootCount != null -> elapsedRemaining(store, state, elapsed, bootCount)
            state.legacyUntil != 0L -> migrate(store, state, wall, elapsed, bootCount)
            else -> discard(store, state)
        }
    }

    private fun elapsedRemaining(store: Store, state: State, elapsed: Long, bootCount: Int): Long {
        val valid = state.bootCount == bootCount && state.startedElapsed >= 0 &&
            state.untilElapsed > state.startedElapsed &&
            state.untilElapsed - state.startedElapsed <= DURATION_MS && elapsed >= state.startedElapsed
        return if (valid && elapsed < state.untilElapsed) state.untilElapsed - elapsed else discard(store, state)
    }

    private fun migrate(store: Store, state: State, wall: Long, elapsed: Long, bootCount: Int): Long {
        val remaining = if (active(state.legacyUntil, wall)) state.legacyUntil - wall else 0L
        if (remaining == 0L || elapsed > Long.MAX_VALUE - remaining) return discard(store, state)
        val saved = store.write(State(bootCount, elapsed, elapsed + remaining))
        if (!saved) clear(store)
        return if (saved) remaining else 0L
    }

    private fun discard(store: Store, state: State): Long {
        if (state != State()) clear(store)
        return 0L
    }
}
