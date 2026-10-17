package ru.tgwatch

enum class HistoryFailure { READ, WRITE, REPAIR, BACKPRESSURE }
class HistoryUnavailableException(val failure: HistoryFailure) : IllegalStateException("History unavailable: $failure")

sealed class HistoryRead<out T> {
    data class Ready<T>(val value: T) : HistoryRead<T>()
    data class Unavailable<T>(val lastKnown: T?, val failure: HistoryFailure) : HistoryRead<T>()

    fun valueOrThrow(): T = when (this) {
        is Ready -> value
        is Unavailable -> throw HistoryUnavailableException(failure)
    }
}
