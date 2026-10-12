package ru.tgwatch

import java.net.URI
import java.net.URISyntaxException

/** Android-independent validation for user-supplied HTTPS addresses. */
object HttpsUrlPolicy {
    private const val MAX_PORT = 65_535
    private const val FIRST_PRINTABLE = '!'
    private const val LAST_PRINTABLE = '~'

    /**
     * Returns the trimmed address when it is an absolute `https://host[:port][/path]` URL of at most
     * [maxLength] printable ASCII characters, otherwise null. User info and fragments are always
     * rejected; a query is accepted only when [allowQuery] is true. Hosts must be ASCII (punycode).
     */
    fun normalize(raw: String?, maxLength: Int, allowQuery: Boolean): String? {
        val text = raw?.trim().orEmpty()
        val uri = if (plain(text, maxLength)) parse(text) else null
        return text.takeIf { uri != null && acceptable(uri, allowQuery) }
    }

    private fun plain(text: String, maxLength: Int): Boolean = text.length in 1..maxLength &&
        text.all { it in FIRST_PRINTABLE..LAST_PRINTABLE } && text.startsWith("https://", ignoreCase = true)

    private fun parse(text: String): URI? = try { URI(text) } catch (_: URISyntaxException) { null }

    private fun acceptable(uri: URI, allowQuery: Boolean): Boolean =
        uri.isAbsolute && "https".equals(uri.scheme, ignoreCase = true) && !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..MAX_PORT) && (allowQuery || uri.rawQuery == null)

    /** Host only, for display without revealing a path, topic or query. */
    fun hostLabel(url: String): String = parse(url)?.host.orEmpty()
}
