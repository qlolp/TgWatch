package ru.tgwatch

/** Throttling and durable admission for a check handed to an existing service instance only. */
internal object AutomationCheckAdmission {
    enum class Result { ACCEPTED, RATE_LIMITED, UNAVAILABLE }

    fun request(elapsed: Long, lastElapsed: Long?, handoff: (() -> Boolean) -> Boolean,
        record: () -> Boolean): Result {
        if (!AutomationPolicy.checkAllowed(elapsed, lastElapsed)) return Result.RATE_LIMITED
        return if (handoff(record)) Result.ACCEPTED else Result.UNAVAILABLE
    }
}
