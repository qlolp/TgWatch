package ru.tgwatch

import android.content.Context

enum class PowerProfile(val titleRes: Int, val awake: Int, val asleep: Int, val failure: Int) {
    ECONOMY(R.string.profile_economy, 60, 300, 60),
    BALANCED(R.string.profile_balanced, 30, 120, 30),
    FREQUENT(R.string.profile_frequent, 15, 60, 10);

    fun title(ctx: Context): String = ctx.getString(titleRes)

    fun interval(bad: Boolean, screenOff: Boolean): Int =
        if (bad) failure else if (screenOff) asleep else awake
}

object ServiceHealth {
    fun isOverdue(lastProgress: Long, now: Long, expectedSec: Int): Boolean =
        lastProgress <= 0L || now < lastProgress || now - lastProgress > expectedSec.coerceAtLeast(10) * 3_000L + 30_000L
}
