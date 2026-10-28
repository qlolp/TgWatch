package ru.tgwatch

import java.net.URI
import java.util.Base64

/**
 * Convenience preset for an ntfy topic. It only composes an ordinary HTTPS webhook address, so delivery, limits,
 * deadlines and privacy are exactly those of the webhook recipient that stores the result; there is no second
 * network worker. The composed address (topic, optional `?auth=` token) is a credential.
 */
internal object NtfyPreset {
    const val DEFAULT_SERVER = "https://ntfy.sh"
    const val MAX_TOPIC_LENGTH = 64
    const val MAX_TOKEN_LENGTH = 64
    private val topicPattern = Regex("[A-Za-z0-9_-]{1,$MAX_TOPIC_LENGTH}")
    private val tokenPattern = Regex("tk_[A-Za-z0-9]{1,${MAX_TOKEN_LENGTH - 3}}")

    /** Null when any part is invalid. A blank server means ntfy.sh; a blank token means anonymous access. */
    fun url(server: String?, topic: String?, token: String?): String? {
        val base = server?.trim().orEmpty().ifEmpty { DEFAULT_SERVER }.trimEnd('/')
        val cleanTopic = topic?.trim().orEmpty()
        val cleanToken = token?.trim().orEmpty()
        val serverOnly = HttpsUrlPolicy.normalize(base, WebhookPolicy.MAX_URL_LENGTH, allowQuery = false)
            ?.let { URI(it).rawPath.isNullOrEmpty() } == true
        val validToken = cleanToken.isEmpty() || tokenPattern.matches(cleanToken)
        return if (serverOnly && validToken && topicPattern.matches(cleanTopic))
            WebhookPolicy.normalizeUrl("$base/$cleanTopic${auth(cleanToken)}") else null
    }

    private fun auth(token: String): String = if (token.isEmpty()) "" else "?auth=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(("Bearer " + token).toByteArray(Charsets.UTF_8))
}
