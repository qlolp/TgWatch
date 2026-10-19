package ru.tgwatch

internal data class WebhookPendingEvent(val id: Long, val event: WebhookEvent, val refreshedElapsed: Long) {
    fun fresh(now: Long): Boolean = now >= refreshedElapsed && now - refreshedElapsed <=
        checkNotNull(event.observation).expectedIntervalSec * FRESH_INTERVALS * MILLIS_PER_SECOND
    private companion object {
        const val FRESH_INTERVALS = 3L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/** Four coalesced events; successful delivery only acknowledges its exact candidate. */
internal class WebhookPendingEvents {
    private var sequence = 0L
    private val candidates = linkedMapOf<String, WebhookPendingEvent>()
    val values: Collection<WebhookPendingEvent> get() = candidates.values

    fun published(event: WebhookEvent?, observation: WebhookObservation, now: Long) {
        if (!WebhookStatuses.accepts(observation)) return
        if (event != null) candidates[observation.serviceId] = WebhookPendingEvent(++sequence, event, now)
        else candidates[observation.serviceId]?.takeIf {
            WebhookStatuses.normalize(it.event.observation?.status) == WebhookStatuses.normalize(observation.status)
        }?.let { candidates[observation.serviceId] = it.copy(
            event = it.event.copy(observation = observation), refreshedElapsed = now) }
    }
    fun delivered(id: Long?) { candidates.entries.removeAll { it.value.id == id } }
    fun forget(serviceId: String) { candidates.remove(serviceId) }
    fun reset() { candidates.clear() }
}
