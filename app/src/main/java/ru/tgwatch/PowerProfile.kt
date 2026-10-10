package ru.tgwatch

enum class PowerProfile(val title: String, val awake: Int, val asleep: Int, val failure: Int) {
    ECONOMY("Экономичный", 60, 300, 60),
    BALANCED("Обычный", 30, 120, 30),
    FREQUENT("Частый", 15, 60, 10);

    fun interval(bad: Boolean, screenOff: Boolean): Int =
        if (bad) failure else if (screenOff) asleep else awake
}

object ServiceHealth {
    fun isOverdue(lastProgress: Long, now: Long, expectedSec: Int): Boolean =
        lastProgress <= 0L || now < lastProgress || now - lastProgress > expectedSec.coerceAtLeast(10) * 3_000L + 30_000L
}
