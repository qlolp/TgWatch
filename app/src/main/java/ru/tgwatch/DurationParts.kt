package ru.tgwatch

/** Normalized, nonnegative duration. Rounding to whole seconds matches observed-time presentation. */
data class DurationParts(val days: Long, val hours: Int, val minutes: Int, val seconds: Int) {
    init {
        require(days in 0..MAX_DAYS && hours in 0 until HOURS_PER_DAY &&
            minutes in 0 until UNITS_PER_HOUR && seconds in 0 until UNITS_PER_HOUR)
    }
    val totalHours: Long get() = days * HOURS_PER_DAY + hours
    fun text(precise: Boolean = false): TextValue = if (precise) preciseText() else when {
        days > 0 && hours > 0 -> textOf(TextKey.DURATION_DAYS_HOURS, TextArgument.Number(days),
            TextArgument.Number(hours.toLong()))
        days > 0 -> textOf(TextKey.DURATION_DAYS, TextArgument.Number(days))
        hours > 0 && minutes > 0 -> textOf(TextKey.DURATION_HOURS_MINUTES, TextArgument.Number(hours.toLong()),
            TextArgument.Number(minutes.toLong()))
        hours > 0 -> textOf(TextKey.DURATION_HOURS, TextArgument.Number(hours.toLong()))
        minutes > 0 -> textOf(TextKey.DURATION_MINUTES, TextArgument.Number(minutes.toLong()))
        else -> textOf(TextKey.DURATION_SECONDS, TextArgument.Number(seconds.toLong()))
    }
    private fun preciseText(): TextValue = when {
        totalHours > 0 -> textOf(TextKey.RECOVERY_HOURS_MINUTES_SECONDS, TextArgument.Number(totalHours),
            TextArgument.Number(minutes.toLong()), TextArgument.Number(seconds.toLong()))
        minutes > 0 -> textOf(TextKey.RECOVERY_MINUTES_SECONDS, TextArgument.Number(minutes.toLong()),
            TextArgument.Number(seconds.toLong()))
        else -> textOf(TextKey.DURATION_SECONDS, TextArgument.Number(seconds.toLong()))
    }
    companion object {
        private const val MS_PER_SECOND = 1_000L
        private const val UNITS_PER_HOUR = 60
        private const val HOURS_PER_DAY = 24
        private const val SECONDS_PER_HOUR = 3_600
        private const val SECONDS_PER_DAY = 86_400
        private const val MAX_DAYS = Long.MAX_VALUE / MS_PER_SECOND / SECONDS_PER_DAY
        fun from(millis: Long): DurationParts {
            val seconds = (millis / MS_PER_SECOND).coerceAtLeast(0L)
            return DurationParts(seconds / SECONDS_PER_DAY, (seconds / SECONDS_PER_HOUR % HOURS_PER_DAY).toInt(),
                (seconds / UNITS_PER_HOUR % UNITS_PER_HOUR).toInt(), (seconds % UNITS_PER_HOUR).toInt())
        }
    }
}
