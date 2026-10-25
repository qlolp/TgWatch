package ru.tgwatch

enum class NetworkKind { WIFI, CELLULAR, VPN, ETHERNET, OTHER, UNKNOWN }
enum class WebhookFormat { TEXT, JSON, JSON_V2 }
enum class WebhookEventKind(val wireName: String) {
    UNREACHABLE("unreachable"), PARTIAL("partial"), NETWORK_LOST("network_lost"),
    RECOVERY("recovery"), NETWORK_RESTORED("network_restored"), TEST("test"), HEARTBEAT("heartbeat");
    companion object { val DELIVERY_EVENTS: Set<WebhookEventKind> =
        setOf(UNREACHABLE, PARTIAL, NETWORK_LOST, RECOVERY, NETWORK_RESTORED) }
}

data class WebhookObservation(val serviceId: String, val status: String, val checkedAtMs: Long,
    val latencyMs: Long?, val network: NetworkKind, val revision: Long, val measured: Boolean, val stale: Boolean,
    val expectedIntervalSec: Int = TargetDefinition.DEFAULT_TELEGRAM_SEC) {
    init { require(expectedIntervalSec in TargetDefinition.MIN_TELEGRAM_SEC..TargetPowerPolicy.MAX_EFFECTIVE_SEC) }
}
data class WebhookEvent(val kind: WebhookEventKind, val observation: WebhookObservation?, val previousStatus: String?)

internal object WebhookStatuses {
    val services: Set<String> = TargetId.entries.map { it.wire }.toSet()
    fun normalize(status: String?): String? = when (status) {
        "TG_DOWN", "DOWN" -> "DOWN"
        "OK", "PARTIAL", "NO_NETWORK" -> status
        else -> null
    }
    fun accepts(observation: WebhookObservation): Boolean = observation.measured && !observation.stale &&
        observation.checkedAtMs > 0L && observation.serviceId in services && normalize(observation.status) != null
}

/** Measured baseline is independent of delivery, retries and filters. At most four built-in IDs. */
class WebhookEventTracker {
    private val previous = mutableMapOf<String, String>()
    fun forget(serviceId: String) { previous.remove(serviceId) }
    fun accepts(observation: WebhookObservation): Boolean = WebhookStatuses.accepts(observation)

    fun observe(observation: WebhookObservation): WebhookEvent? {
        if (!accepts(observation)) return null
        val status = checkNotNull(WebhookStatuses.normalize(observation.status))
        val before = previous.put(observation.serviceId, status)
        return if (status == before || before == null && status == "OK") null else {
            val kind = when {
                status == "NO_NETWORK" -> WebhookEventKind.NETWORK_LOST
                before == "NO_NETWORK" -> WebhookEventKind.NETWORK_RESTORED
                status == "OK" -> WebhookEventKind.RECOVERY
                status == "PARTIAL" -> WebhookEventKind.PARTIAL
                else -> WebhookEventKind.UNREACHABLE
            }
            WebhookEvent(kind, observation.copy(status = status), before)
        }
    }
}
