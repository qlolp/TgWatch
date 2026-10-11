package ru.tgwatch

import java.math.BigInteger

/** Exact nonnegative latency totals; individual valid Long values can overflow an ordinary sum. */
class LatencyTotal(initial: Long = 0L) {
    private var sum = BigInteger.valueOf(initial.coerceAtLeast(0L))
    val saturatedSum: Long get() = sum.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    fun add(value: Long) { sum = sum.add(BigInteger.valueOf(value.coerceAtLeast(0L))) }
    fun merge(other: LatencyTotal) { sum = sum.add(other.sum) }
    fun average(count: Int): Long = if (count <= 0) -1L else
        sum.divide(BigInteger.valueOf(count.toLong())).min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    fun copy(): LatencyTotal = LatencyTotal().also { it.sum = sum }
}
