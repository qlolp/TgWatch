package ru.tgwatch

enum class PowerProfile(val awake: Int, val asleep: Int, val failure: Int) {
    ECONOMY(60, 300, 60),
    BALANCED(30, 120, 30),
    FREQUENT(15, 60, 10);

    /** Follows the current UI language, so it is computed rather than stored. */
    val title: String get() = when (this) {
        ECONOMY -> Lang.t("Экономичный", "Economy")
        BALANCED -> Lang.t("Обычный", "Balanced")
        FREQUENT -> Lang.t("Частый", "Frequent")
    }

    fun interval(bad: Boolean, screenOff: Boolean): Int =
        if (bad) failure else if (screenOff) asleep else awake
}

object ServiceHealth {
    fun isOverdue(lastProgress: Long, now: Long, expectedSec: Int): Boolean =
        lastProgress <= 0L || now < lastProgress || now - lastProgress > expectedSec.coerceAtLeast(10) * 3_000L + 30_000L
}
