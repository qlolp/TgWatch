package ru.tgwatch

/** Existing coordinator fixtures use a fixed, measured observation without Android state or disk access. */
internal fun WebhookCoordinator.onPublished(status: String) = onPublished(
    WebhookObservation("telegram", status, 1L, null, NetworkKind.UNKNOWN, 0L, true, false))
