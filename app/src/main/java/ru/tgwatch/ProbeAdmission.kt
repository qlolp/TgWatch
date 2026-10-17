package ru.tgwatch

import java.util.concurrent.atomic.AtomicInteger

enum class ProbeLane { PRIMARY, SECONDARY }
data class ProbeAdmissionSnapshot(val primary: Int, val secondary: Int)

/** A permit owns physical work, including work that ignores Future.cancel or service shutdown. */
class ProbePermit internal constructor(private val release: () -> Unit) {
    private val state = AtomicInteger(0)
    fun start(): Boolean = state.compareAndSet(0, 1)
    fun cancelBeforeStart() { if (state.compareAndSet(0, 2)) release() }
    fun finished() { if (state.compareAndSet(1, 2)) release() }
}

/** One bounded registry per process; replacing executors cannot forget live native requests. */
class ProbeAdmission(private val primaryLimit: Int = PRIMARY_LIMIT, private val secondaryLimit: Int = SECONDARY_LIMIT) {
    init {
        require(primaryLimit >= 0 && secondaryLimit >= 0 && primaryLimit.toLong() + secondaryLimit > 0)
    }
    private val active = mutableMapOf<String, ProbeLane>()
    private var primary = 0
    private var secondary = 0

    @Synchronized fun tryAcquire(key: String, lane: ProbeLane): ProbePermit? {
        val full = when (lane) {
            ProbeLane.PRIMARY -> primary >= primaryLimit
            ProbeLane.SECONDARY -> secondary >= secondaryLimit
        }
        if (full || key in active) return null
        active[key] = lane
        if (lane == ProbeLane.PRIMARY) primary++ else secondary++
        return ProbePermit { release(key) }
    }

    @Synchronized private fun release(key: String) {
        when (active.remove(key)) {
            ProbeLane.PRIMARY -> primary--
            ProbeLane.SECONDARY -> secondary--
            null -> Unit
        }
    }

    @Synchronized fun snapshot() = ProbeAdmissionSnapshot(primary, secondary)

    companion object {
        const val PRIMARY_LIMIT = 16
        const val SECONDARY_LIMIT = 3
        val process = ProbeAdmission()
    }
}
