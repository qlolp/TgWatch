package ru.tgwatch

import org.json.JSONObject

data class WebhookBody(val contentType: String, val bytes: ByteArray)

/** Versioned allowlist. No Context, history lookup, free-form names or diagnostic fields. */
object WebhookPayload {
    fun encode(event: WebhookEvent, format: WebhookFormat): WebhookBody = when (format) {
        WebhookFormat.JSON -> WebhookBody("application/json; charset=utf-8", json(event).toByteArray(Charsets.UTF_8))
        WebhookFormat.TEXT -> WebhookBody("text/plain; charset=utf-8", text(event).toByteArray(Charsets.UTF_8))
    }

    private fun json(event: WebhookEvent): String {
        val observation = event.observation.takeUnless { event.kind == WebhookEventKind.TEST }
        return JSONObject().put("schema_version", 1)
            .put("service_id", observation?.serviceId?.takeIf { it in WebhookStatuses.services }
                ?: if (event.kind == WebhookEventKind.TEST) "telegram" else "unknown")
            .put("event", event.kind.wireName)
            .put("status", WebhookStatuses.normalize(observation?.status) ?: JSONObject.NULL)
            .put("previous_status", WebhookStatuses.normalize(event.previousStatus) ?: JSONObject.NULL)
            .put("checked_at_ms", observation?.checkedAtMs?.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("latency_ms", observation?.latencyMs?.takeIf { it >= 0L } ?: JSONObject.NULL)
            .put("network", (observation?.network ?: NetworkKind.UNKNOWN).name)
            .toString()
    }

    private fun text(event: WebhookEvent): String {
        if (event.kind == WebhookEventKind.TEST) return WebhookPolicy.testPayload()
        val observation = checkNotNull(event.observation)
        val status = WebhookStatuses.normalize(observation.status)
        val legacy = if (status == "DOWN") "TG_DOWN" else status.orEmpty()
        return if (observation.serviceId == TargetId.TELEGRAM.wire) checkNotNull(WebhookPolicy.payload(legacy)) else {
            val name = TargetNames.label(checkNotNull(TargetId.fromWire(observation.serviceId)))
            when (status) {
                "OK" -> Lang.t("TG Монитор: $name доступен", "TG Monitor: $name is reachable")
                "DOWN" -> Lang.t("TG Монитор: $name недоступен", "TG Monitor: $name is unreachable")
                "PARTIAL" -> Lang.t("TG Монитор: $name доступен частично", "TG Monitor: $name is partially reachable")
                "NO_NETWORK" -> Lang.t("TG Монитор: $name — нет сети", "TG Monitor: $name — no network")
                else -> error("Unknown target status")
            }
        }
    }
}
