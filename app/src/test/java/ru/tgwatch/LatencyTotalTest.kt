package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class LatencyTotalTest {
    private fun total(initial: Long = 0L) = LatencyTotal(initial)
    private fun add(total: LatencyTotal, value: Long) = total.add(value)
    private fun average(total: LatencyTotal, count: Int) = total.average(count)
    private fun merge(target: LatencyTotal, source: LatencyTotal) = target.merge(source)
    @Test fun repeatedLongMaximumSamplesKeepExactAverage() {
        val total = total()
        repeat(3) { add(total, Long.MAX_VALUE) }
        assertEquals(Long.MAX_VALUE, average(total, 3))
        assertEquals(Long.MAX_VALUE, total.saturatedSum)
    }
    @Test fun mergingOverflowedTotalsPreservesWeightedAverage() {
        val first = total()
        add(first, Long.MAX_VALUE); add(first, Long.MAX_VALUE)
        val second = total(0)
        add(second, 0)
        merge(second, first)
        assertEquals(6148914691236517204L, average(second, 3))
        assertEquals(Long.MAX_VALUE, average(first, 2))
    }
    @Test fun copyDoesNotAliasTheAccumulatorAndEmptyAverageIsUnknown() {
        val first = total(9)
        val copy = first.copy()
        add(copy, 11)
        assertEquals(9L, average(first, 1))
        assertEquals(10L, average(copy, 2))
        assertEquals(-1L, average(total(), 0))
    }
}
