package ru.tgwatch

/** Android-independent webhook rules: address validation, payload text and request identity. */
object WebhookPolicy {
    const val MAX_URL_LENGTH = 512
    /** Connect and read timeouts, each. */
    const val TIMEOUT_MS = 5_000
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
    fun payload(status: String): String? = when (status) {
        "OK" -> Lang.t("TG Монитор: Telegram доступен", "TG Monitor: Telegram is reachable")
        "PARTIAL" -> Lang.t("TG Монитор: Telegram доступен частично",
            "TG Monitor: Telegram is partially reachable")
        "TG_DOWN" -> Lang.t("TG Монитор: Telegram недоступен",
            "TG Monitor: Telegram is unreachable")
        "NO_NETWORK" -> Lang.t("TG Монитор: нет сети", "TG Monitor: no network")
        else -> null
    }

    fun testPayload(): String =
        Lang.t("TG Монитор: тестовое сообщение", "TG Monitor: test message")

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
class WebhookGate(private val minGapMs: Long = WebhookPolicy.MIN_GAP_MS,
    private val testGapMs: Long = WebhookPolicy.TEST_GAP_MS) {
    private var lastSent: String? = null
    private var previousSent: String? = null
    private var inFlight = false
    private var lastTestAt: Long? = null
    private val sentAt = HashMap<String, Long>()

    /** Forget delivery history after the address or switch changes; an active request stays counted. */
    @Synchronized fun reset() {
        lastSent = null
        previousSent = null
        sentAt.clear()
    }

    /** True when the caller must post [status] now and later call [finished]. */
    @Synchronized fun begin(status: String, nowElapsed: Long): Boolean {
        val last = lastSent
        // A monitor that starts healthy is not news; the first change after it is.
        val baseline = !inFlight && last == null && status == "OK"
        if (baseline) lastSent = status
        val send = !baseline && !inFlight && WebhookPolicy.notifies(status) && status != last &&
            gapAllows(sentAt[status], nowElapsed, minGapMs)
        if (send) {
            previousSent = last
            lastSent = status
            sentAt[status] = nowElapsed
            inFlight = true
        }
        return send
    }

    /** A failed delivery is retried once the per-status gap allows it. */
    @Synchronized fun finished(status: String, delivered: Boolean) {
        inFlight = false
        if (!delivered && lastSent == status) lastSent = previousSent
    }

    @Synchronized fun beginTest(nowElapsed: Long): Boolean {
        val send = !inFlight && gapAllows(lastTestAt, nowElapsed, testGapMs)
        if (send) {
            lastTestAt = nowElapsed
            inFlight = true
        }
        return send
    }

    /** Elapsed time going backwards (a new boot cannot share this process) never blocks a post. */
    private fun gapAllows(previous: Long?, now: Long, gap: Long): Boolean =
        previous == null || now < previous || now - previous >= gap

    @Synchronized fun finishedTest() {
        inFlight = false
    }
}
