package ru.tgwatch

/** Ages use the same monotonic clock as delivery. This text is never persisted or exported. */
internal object WebhookHealthText {
    private const val MS_PER_SECOND = 1_000L

    fun format(health: WebhookHealth, nowElapsed: Long): String {
        val outcome = when (health.outcome) {
            WebhookOutcome.IDLE -> Lang.t("попыток не было", "no attempts")
            WebhookOutcome.SENDING -> Lang.t("отправляется", "sending")
            WebhookOutcome.SENT -> Lang.t("доставлено", "delivered")
            WebhookOutcome.FAILED -> Lang.t("ошибка доставки", "delivery failed")
            WebhookOutcome.TIMED_OUT -> Lang.t("время ожидания истекло", "timed out")
        }
        val attempt = age(health.lastAttemptElapsed, nowElapsed)
        val success = age(health.lastSuccessElapsed, nowElapsed)
        val flight = if (health.inFlight) Lang.t("да", "yes") else Lang.t("нет", "no")
        return Lang.t("Доставка webhook (в этом процессе)", "Webhook delivery (this process)") + "\n" +
            Lang.t("Последняя попытка: ", "Last attempt: ") + attempt + "\n" +
            Lang.t("Последний успех: ", "Last success: ") + success + "\n" +
            Lang.t("Результат: ", "Outcome: ") + outcome + "\n" +
            Lang.t("Запрос ещё выполняется: ", "Request still running: ") + flight
    }

    private fun age(at: Long?, now: Long): String = if (at == null) Lang.t("нет", "none") else {
        val seconds = (now - at).coerceAtLeast(0) / MS_PER_SECOND
        Lang.t("$seconds с назад", "$seconds s ago")
    }
}
