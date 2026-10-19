package ru.tgwatch

import org.json.JSONObject

data class WebhookBody(val contentType: String, val bytes: ByteArray, val textValue: TextValue? = null)

/** Versioned allowlist. No Context, history lookup, free-form names or diagnostic fields. */
object WebhookPayload {
    fun encode(event: WebhookEvent, format: WebhookFormat, sentAtMs: Long? = null): WebhookBody = when (format) {
        WebhookFormat.JSON, WebhookFormat.JSON_V2 -> WebhookBody("application/json; charset=utf-8",
            json(event, format, sentAtMs).toByteArray(Charsets.UTF_8))
        WebhookFormat.TEXT -> WebhookBody("text/plain; charset=utf-8", byteArrayOf(), text(event))
    }

    private fun json(event: WebhookEvent, format: WebhookFormat, sentAtMs: Long?): String {
        val observation = event.observation.takeUnless { event.kind == WebhookEventKind.TEST }
        val version2 = format == WebhookFormat.JSON_V2
        if (version2) {
            require(sentAtMs != null && sentAtMs > 0L)
            require(event.kind == WebhookEventKind.TEST || observation?.let(WebhookStatuses::accepts) == true)
        }
        return JSONObject().put("schema_version", if (version2) 2 else 1)
            .put("service_id", observation?.serviceId?.takeIf { it in WebhookStatuses.services }
                ?: if (event.kind == WebhookEventKind.TEST) "telegram" else "unknown")
            .put("event", event.kind.wireName)
            .put("status", WebhookStatuses.normalize(observation?.status) ?: JSONObject.NULL)
            .put("previous_status", WebhookStatuses.normalize(event.previousStatus
                .takeUnless { event.kind == WebhookEventKind.HEARTBEAT }) ?: JSONObject.NULL)
            .put("checked_at_ms", observation?.checkedAtMs?.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("latency_ms", observation?.latencyMs?.takeIf { it >= 0L } ?: JSONObject.NULL)
            .put("network", (observation?.network ?: NetworkKind.UNKNOWN).name)
            .apply { if (version2) {
                put("sent_at_ms", sentAtMs)
                put("monitoring", observation != null)
                put("is_stale", observation == null)
            } }
            .toString()
    }

    private fun text(event: WebhookEvent): TextValue {
        if (event.kind == WebhookEventKind.TEST) return WebhookPolicy.testPayload()
        val observation = checkNotNull(event.observation)
        val status = WebhookStatuses.normalize(observation.status)
        val legacy = if (status == "DOWN") "TG_DOWN" else status.orEmpty()
        return if (observation.serviceId == TargetId.TELEGRAM.wire) checkNotNull(WebhookPolicy.payload(legacy)) else {
            val name = TargetNames.text(checkNotNull(TargetId.fromWire(observation.serviceId)))
            when (status) {
                "OK" -> textOf(TextKey.COPY_WEBHOOK_PAYLOAD_TG_MONITOR_IS_REACHABLE, textArgument(name))
                "DOWN" -> textOf(TextKey.COPY_WEBHOOK_PAYLOAD_TG_MONITOR_IS_UNREACHABLE, textArgument(name))
                "PARTIAL" -> textOf(TextKey.COPY_WEBHOOK_PAYLOAD_TG_MONITOR_IS_PARTIALLY, textArgument(name))
                "NO_NETWORK" -> textOf(TextKey.COPY_WEBHOOK_PAYLOAD_TG_MONITOR_NO_NETWORK, textArgument(name))
                else -> error("Unknown target status")
            }
        }
    }
}
