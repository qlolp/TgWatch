package ru.tgwatch

/** Ages use the same monotonic clock as delivery. This text is never persisted or exported. */
internal object WebhookHealthText {
    private const val MS_PER_SECOND = 1_000L

    fun format(health: WebhookHealth, nowElapsed: Long): TextValue {
        val outcome = when (health.outcome) {
            WebhookOutcome.IDLE -> textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_NO_ATTEMPTS)
            WebhookOutcome.SENDING -> textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_SENDING)
            WebhookOutcome.SENT -> textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_DELIVERED)
            WebhookOutcome.FAILED -> textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_DELIVERY_FAILED)
            WebhookOutcome.TIMED_OUT -> textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_TIMED_OUT)
        }
        val attempt = age(health.lastAttemptElapsed, nowElapsed)
        val success = age(health.lastSuccessElapsed, nowElapsed)
        val flight = if (health.inFlight)
            textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_YES)
            else textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_NO)
        val block = if (health.transportBlocked) textLiteral("\n") +
            textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_DELIVERY_BLOCKED_THE_PREVIOUS)
            else textLiteral("")
        return textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_WEBHOOK_DELIVERY_THIS_PROCESS) + "\n" +
            textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_LAST_ATTEMPT) + attempt + "\n" +
            textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_LAST_SUCCESS) + success + "\n" +
            textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_OUTCOME) + outcome + "\n" +
            textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_REQUEST_STILL_RUNNING) + flight + block
    }

    private fun age(at: Long?,
        now: Long): TextValue = if (at == null) textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_NONE) else {
        val seconds = (now - at).coerceAtLeast(0) / MS_PER_SECOND
        textOf(TextKey.COPY_WEBHOOK_HEALTH_TEXT_S_AGO, textArgument(seconds))
    }
}
