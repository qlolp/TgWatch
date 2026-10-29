package ru.tgwatch

import java.util.ArrayDeque

/** Bounded canonicalization without allocating a boxed map key for every observation. */
internal object HistoryRetention {
    private val order = Comparator<Observation> { left, right ->
        if (left.clockEpoch == right.clockEpoch) left.at.compareTo(right.at)
        else left.clockEpoch.compareTo(right.clockEpoch)
    }

    fun retain(samples: List<Observation>, from: Long, limit: Int): List<Observation> {
        require(limit > 0)
        if (samples.isEmpty()) return emptyList()
        // Stable sorting preserves "last real result wins" for equal epoch/timestamp keys.
        val ordered = if (isOrdered(samples)) samples else samples.sortedWith(order)
        val result = ArrayDeque<Observation>()
        var pending = ordered.first()
        for (index in 1 until ordered.size) {
            val row = ordered[index]
            if (order.compare(pending, row) == 0) {
                if (!row.isEpochMetadata) pending = row
            } else {
                if (pending.until > from) append(result, pending, limit)
                pending = row
            }
        }
        if (pending.until > from) append(result, pending, limit)
        if (result.peekLast()?.clockEpoch != pending.clockEpoch) {
            append(result, pending.copy(until = pending.at, kind = "UNKNOWN", latencyMs = -1), limit)
        }
        return result.toList()
    }

    private fun isOrdered(rows: List<Observation>): Boolean {
        for (index in 1 until rows.size) {
            if (order.compare(rows[index - 1], rows[index]) > 0) return false
        }
        return true
    }

    private fun append(rows: ArrayDeque<Observation>, row: Observation, limit: Int) {
        if (rows.size == limit) rows.removeFirst()
        rows.addLast(row)
    }
}
