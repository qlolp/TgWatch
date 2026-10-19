package ru.tgwatch

/** One credential-storage snapshot; generation distinguishes replacements with the same URL. */
internal data class WebhookConfiguration(val url: String?, val enabled: Boolean, val generation: Long,
    val format: WebhookFormat = WebhookFormat.TEXT,
    val events: Set<WebhookEventKind> = WebhookEventKind.DELIVERY_EVENTS,
    val services: Set<String> = WebhookStatuses.services) {
    fun admits(captured: WebhookConfiguration, observation: WebhookObservation): Boolean =
        captured == this && observation.serviceId in services
}

/** Compare one complete credential configuration; transports keep their own physical identity. */
internal class WebhookConfigurationFence {
    private var installed: WebhookConfiguration? = null
    fun changed(current: WebhookConfiguration): Boolean {
        val changed = current != installed
        installed = current
        return changed
    }
}

/** Android-independent webhook rules: address validation, payload text and request identity. */
object WebhookPolicy {
    const val MAX_URL_LENGTH = 512
    /** Connect and read timeouts, each. */
    const val TIMEOUT_MS = 5_000
    /** Total monotonic budget for a result, including queued work and DNS. */
    const val RESULT_DEADLINE_MS = 10_000L
    /** Minimum time between two posts of the same status; the first post of a status is immediate. */
    const val MIN_GAP_MS = 60_000L
    const val TEST_GAP_MS = 10_000L
    private const val MAX_VERSION_LENGTH = 32
    private const val HTTP_SUCCESS_FIRST = 200
    private const val HTTP_SUCCESS_LAST = 299
    private val NOTIFIED = setOf("OK", "PARTIAL", "TG_DOWN", "NO_NETWORK")

    /** HTTPS only, no user info or fragment; a query (for example ntfy options) is allowed. */
    fun normalizeUrl(raw: String?): String? = HttpsUrlPolicy.normalize(raw, MAX_URL_LENGTH, allowQuery = true)

    fun notifies(status: String): Boolean = status in NOTIFIED

    /** One short status line. No history, diagnostics, endpoints, addresses or credentials. */
    fun payload(status: String): TextValue? = when (status) {
        "OK" -> textOf(TextKey.COPY_WEBHOOK_POLICY_TG_MONITOR_TELEGRAM_IS)
        "PARTIAL" -> textOf(TextKey.COPY_WEBHOOK_POLICY_TG_MONITOR_TELEGRAM_IS_E25A0D)
        "TG_DOWN" -> textOf(TextKey.COPY_WEBHOOK_POLICY_TG_MONITOR_TELEGRAM_IS_B13B5F)
        "NO_NETWORK" -> textOf(TextKey.COPY_WEBHOOK_POLICY_TG_MONITOR_NO_NETWORK)
        else -> null
    }

    fun testPayload(): TextValue =
        textOf(TextKey.COPY_WEBHOOK_POLICY_TG_MONITOR_TEST_MESSAGE)

    fun userAgent(versionName: String?): String {
        val version = versionName?.takeIf { it.isNotEmpty() && it.length <= MAX_VERSION_LENGTH &&
            it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-+_" } } ?: "unknown"
        return "TgWatch/$version (Android)"
    }

    fun delivered(httpCode: Int): Boolean = httpCode in HTTP_SUCCESS_FIRST..HTTP_SUCCESS_LAST
}

/**
 * Per-process send decisions. Called for every published status so a post skipped by the rate
 * limit or a failed delivery is retried by a later check. At most one request is in flight.
 */
internal class WebhookGate(private val minGapMs: Long = WebhookPolicy.MIN_GAP_MS,
    private val testGapMs: Long = WebhookPolicy.TEST_GAP_MS) {
    /** Identity belongs to one physical request; callbacks must retain this exact token. */
    class Request internal constructor(internal val generation: Long, internal val status: String?,
        internal val previousSent: String?, internal val heartbeatServiceId: String? = null) {
        internal var superseded = false
    }

    private var lastSent: String? = null
    private var generation = 0L
    private var inFlight: Request? = null
    private var lastTestAt: Long? = null
    private val sentAt = HashMap<String, Long>()

    /** Forget delivery history after the address or switch changes; an active request stays counted. */
    @Synchronized fun reset() {
        generation++
        lastSent = null
        sentAt.clear()
    }

    @Synchronized fun forget(serviceId: String) {
        require(serviceId in WebhookStatuses.services)
        val prefix = "$serviceId:"
        sentAt.keys.removeAll { it.startsWith(prefix) }
        if (lastSent?.startsWith(prefix) == true) lastSent = null
        inFlight?.takeIf {
            it.status?.startsWith(prefix) == true || it.heartbeatServiceId == serviceId
        }?.superseded = true
    }

    /** The token to post [status] now and later pass to [finished], or null when no post is due. */
    @Synchronized fun begin(status: String, nowElapsed: Long): Request? {
        if (inFlight != null) return null
        val last = lastSent
        // A monitor that starts healthy is not news; the first change after it is.
        return when {
            last == null && status == "OK" -> {
                lastSent = status
                null
            }
            WebhookPolicy.notifies(status) && status != last &&
                gapAllows(sentAt[status], nowElapsed, minGapMs) -> Request(generation, status, last).also {
                lastSent = status
                sentAt[status] = nowElapsed
                inFlight = it
            }
            else -> null
        }
    }

    /** Event tracking already established the measured baseline, including filtered initial failures. */
    @Synchronized fun beginEvent(key: String, nowElapsed: Long): Request? {
        if (inFlight != null || !gapAllows(sentAt[key], nowElapsed, minGapMs)) return null
        return Request(generation, key, lastSent).also {
            lastSent = key
            sentAt[key] = nowElapsed
            inFlight = it
        }
    }

    /** Only this physical request can release its slot; older generations cannot change history. */
    @Synchronized fun finished(request: Request, delivered: Boolean): Boolean {
        if (inFlight !== request) return false
        inFlight = null
        val currentGeneration = request.generation == generation && !request.superseded
        if (currentGeneration && request.status != null && !delivered) {
            lastSent = request.previousSent
        }
        return true
    }

    @Synchronized fun beginTest(nowElapsed: Long): Request? {
        if (inFlight != null || !gapAllows(lastTestAt, nowElapsed, testGapMs)) return null
        return Request(generation, null, null).also {
            lastTestAt = nowElapsed
            inFlight = it
        }
    }

    /** Uses the physical slot without touching event history or the explicit-test throttle. */
    @Synchronized fun beginHeartbeat(serviceId: String): Request? {
        require(serviceId in WebhookStatuses.services)
        return if (inFlight != null) null else Request(generation, null, lastSent, serviceId).also { inFlight = it }
    }

    /** Elapsed time going backwards (a new boot cannot share this process) never blocks a post. */
    private fun gapAllows(previous: Long?, now: Long, gap: Long): Boolean =
        previous == null || now < previous || now - previous >= gap
}
