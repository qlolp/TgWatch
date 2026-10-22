package ru.tgwatch

/**
 * Country of the egress address used for outbound HTTPS.
 *
 * Only an ISO 3166-1 alpha-2 code is retained. The address itself is never stored,
 * logged, backed up or shared. Lookup uses one public HTTPS trace document and
 * parses a single `loc=` field; every other line is discarded immediately.
 */
object EgressCountry {
    const val ENDPOINT = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val FIELD = "loc="
    private const val ISO_LENGTH = 2
    private const val REGIONAL_INDICATOR_BASE = 0x1F1E6
    /** Refresh at most this often unless the active network identity changes. */
    const val MIN_INTERVAL_MS = 6 * 60 * 60_000L

    data class Snapshot(val iso: String, val checkedAt: Long, val networkHandle: Long)

    fun isValid(iso: String): Boolean =
        iso.length == ISO_LENGTH && iso.all { it in 'A'..'Z' }

    /** Accepts `loc=US` from the Cloudflare trace format; anything else is ignored. */
    fun parseTrace(body: String): String? {
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith(FIELD, ignoreCase = true)) return@forEach
            val iso = line.substring(FIELD.length).trim().uppercase()
            return iso.takeIf { isValid(it) }
        }
        return null
    }

    /** Regional indicator symbols; invalid codes yield an empty flag. */
    fun flag(iso: String): String {
        if (!isValid(iso)) return ""
        return iso.map { letter ->
            String(Character.toChars(REGIONAL_INDICATOR_BASE + (letter.code - 'A'.code)))
        }.joinToString("")
    }

    fun label(iso: String): String {
        if (!isValid(iso)) return ""
        return "${flag(iso)} $iso"
    }

    /** Flag plus localized country name when the platform knows it; ISO remains the authority. */
    fun displayLabel(iso: String, locale: java.util.Locale): String {
        if (!isValid(iso)) return ""
        val name = java.util.Locale.Builder().setRegion(iso).build().getDisplayCountry(locale).trim()
        val flag = flag(iso)
        return when {
            name.isEmpty() || name.equals(iso, ignoreCase = true) -> "$flag $iso"
            else -> "$flag $name ($iso)"
        }
    }

    fun needsRefresh(stored: Snapshot?, now: Long, networkHandle: Long): Boolean {
        if (stored == null || stored.networkHandle != networkHandle) return true
        return now - stored.checkedAt >= MIN_INTERVAL_MS
    }
}
