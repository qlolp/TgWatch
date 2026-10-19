package ru.tgwatch

/** Publication-driven, four-goal throttle. Only an admitted transport start consumes the interval. */
class WebhookHeartbeatPolicy {
    private val starts = mutableMapOf<String, Long>()
    fun due(observation: WebhookObservation, nowElapsed: Long): Boolean {
        val previous = starts[observation.serviceId]
        return nowElapsed >= 0L && WebhookStatuses.accepts(observation) &&
            (previous == null || nowElapsed < previous || nowElapsed - previous >= INTERVAL_MS)
    }
    fun started(serviceId: String, nowElapsed: Long) {
        if (serviceId in WebhookStatuses.services && nowElapsed >= 0L) starts[serviceId] = nowElapsed
    }
    fun forget(serviceId: String) { starts.remove(serviceId) }
    fun reset() { starts.clear() }
    companion object { const val INTERVAL_MS = 300_000L }
}
