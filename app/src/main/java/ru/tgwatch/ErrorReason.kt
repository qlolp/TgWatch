package ru.tgwatch

/**
 * User-facing failure reasons for UI toasts.
 *
 * Raw exception messages are not shown: they can contain URLs, tokens or other
 * credentials. Only a short semantic class is chosen here; the full throwable
 * stays out of screenshots and accessibility announcements.
 */
internal object ErrorReason {
    enum class Kind { UNKNOWN, PERMISSION, NO_APP, STORAGE, STATE }

    fun kindOf(error: Throwable): Kind {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            kindOfOne(current)?.let { return it }
            current = current.cause
            depth++
        }
        return Kind.UNKNOWN
    }

    fun text(kind: Kind): TextValue = textOf(
        when (kind) {
            Kind.UNKNOWN -> TextKey.ERROR_REASON_UNKNOWN
            Kind.PERMISSION -> TextKey.ERROR_REASON_PERMISSION
            Kind.NO_APP -> TextKey.ERROR_REASON_NO_APP
            Kind.STORAGE -> TextKey.ERROR_REASON_STORAGE
            Kind.STATE -> TextKey.ERROR_REASON_STATE
        })

    fun text(error: Throwable): TextValue = text(kindOf(error))

    private fun kindOfOne(error: Throwable): Kind? = when {
        error is SecurityException -> Kind.PERMISSION
        error is java.io.IOException -> Kind.STORAGE
        error is IllegalStateException || error is java.util.concurrent.RejectedExecutionException -> Kind.STATE
        error.javaClass.simpleName.let { name ->
            name.endsWith("ActivityNotFoundException") || name.endsWith("AndroidException")
        } -> Kind.NO_APP
        else -> null
    }

    private const val MAX_CAUSE_DEPTH = 5
}
