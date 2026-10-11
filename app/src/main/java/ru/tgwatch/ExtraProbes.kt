package ru.tgwatch

/**
 * Opt-in informational HTTPS probes for other services. They use [ProbeGroup.EXTRA], which
 * [ProbeReport.classify] ignores: they never change Telegram status, latency, reason or alerts.
 */
object ExtraProbes {
    const val MATRIX_URL = "https://matrix.org/"
    const val WHATSAPP_URL = "https://web.whatsapp.com/"
    const val MAX_CUSTOM_URL_LENGTH = 256
    /** Separate, shorter budget after the Telegram/control batches. */
    const val BATCH_TIMEOUT_MS = 5_000L

    /** A public HTTPS address without credentials, query or fragment; null when invalid. */
    fun normalizeCustomUrl(raw: String?): String? =
        HttpsUrlPolicy.normalize(raw, MAX_CUSTOM_URL_LENGTH, allowQuery = false)

    fun endpoints(matrix: Boolean, whatsapp: Boolean, custom: Boolean, customUrl: String?): List<ProbeEndpoint> =
        buildList {
            if (matrix) add(ProbeEndpoint("Matrix", ProbeGroup.EXTRA, MATRIX_URL))
            if (whatsapp) add(ProbeEndpoint("WhatsApp Web", ProbeGroup.EXTRA, WHATSAPP_URL))
            val url = if (custom) normalizeCustomUrl(customUrl) else null
            if (url != null) {
                add(ProbeEndpoint(Lang.t("Свой URL", "Custom URL") + " (" + HttpsUrlPolicy.hostLabel(url) + ")",
                    ProbeGroup.EXTRA, url))
            }
        }
}
