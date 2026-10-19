package ru.tgwatch

internal class WebhookHeartbeatCandidate(val observation: WebhookObservation, private val publishedElapsed: Long) {
    fun fresh(now: Long): Boolean = now >= publishedElapsed &&
        now - publishedElapsed <= observation.expectedIntervalSec * FRESH_INTERVALS * MILLIS_PER_SECOND
    private companion object {
        const val FRESH_INTERVALS = 3L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/** At most the latest unsent measurement for each fixed goal, never a transport queue. */
internal class WebhookHeartbeatCandidates {
    private val candidates = linkedMapOf<String, WebhookHeartbeatCandidate>()
    private val policy = WebhookHeartbeatPolicy()

    fun published(observation: WebhookObservation, configuration: WebhookConfiguration, now: Long) {
        if (configuration.format != WebhookFormat.JSON_V2) return
        if (allowed(observation, configuration.events))
            candidates[observation.serviceId] = WebhookHeartbeatCandidate(observation, now)
        else candidates.remove(observation.serviceId)
    }
    fun next(now: Long): WebhookHeartbeatCandidate? {
        candidates.entries.removeAll { !it.value.fresh(now) }
        return candidates.values.firstOrNull { policy.due(it.observation, now) }
    }
    fun started(candidate: WebhookHeartbeatCandidate, now: Long) {
        policy.started(candidate.observation.serviceId, now)
        if (candidates[candidate.observation.serviceId] === candidate)
            candidates.remove(candidate.observation.serviceId)
    }
    fun eventStarted(observation: WebhookObservation, format: WebhookFormat, now: Long) {
        if (format == WebhookFormat.JSON_V2) {
            policy.started(observation.serviceId, now)
            candidates[observation.serviceId]?.takeIf {
                it.observation.revision == observation.revision && it.observation.checkedAtMs == observation.checkedAtMs
            }?.let { candidates.remove(observation.serviceId) }
        }
    }
    fun forget(serviceId: String) { candidates.remove(serviceId); policy.forget(serviceId) }
    fun reset() { candidates.clear(); policy.reset() }

    private fun allowed(observation: WebhookObservation, events: Set<WebhookEventKind>): Boolean =
        WebhookStatuses.accepts(observation) && when (WebhookStatuses.normalize(observation.status)) {
            "OK" -> WebhookEventKind.RECOVERY in events
            "DOWN" -> WebhookEventKind.UNREACHABLE in events
            "PARTIAL" -> WebhookEventKind.PARTIAL in events
            "NO_NETWORK" -> WebhookEventKind.NETWORK_LOST in events
            else -> false
        }
}
