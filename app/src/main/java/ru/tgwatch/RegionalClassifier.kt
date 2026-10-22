package ru.tgwatch

import java.io.StringReader
import java.util.Locale
import ru.tgwatch.probe.StrictJsonReader

/** Explicit service evidence only. A country flag or generic HTTP denial never proves an IP block. */
object RegionalClassifier {
    private val loginHosts = setOf("accounts.google.com", "auth.openai.com", "auth0.openai.com",
        "consent.google.com", "consent.youtube.com")
    private val regionCodes = setOf("unsupported_country_region_territory", "unsupported_country",
        "country_not_supported", "region_not_supported", "unsupported_location", "region_unsupported")
    private val regionPhrases = listOf("not available in your country", "not supported in your country",
        "not available in your region", "unavailable in your country", "недоступен в вашей стране",
        "недоступно в вашей стране", "недоступен в вашем регионе", "не поддерживается в вашей стране")
    private val challengePhrases = listOf("verify you are human", "unusual traffic", "checking your browser",
        "подтвердите, что вы человек", "подозрительный трафик")
    private val ignored = Regex(
        "<(script|style|head|template|noscript|pre|code)\\b[^>]*>(?:.*?</\\1\\s*>|.*\\z)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val comments = Regex("<!--.*?(?:-->|$)", RegexOption.DOT_MATCHES_ALL)
    private val tags = Regex("<[^>]*>")
    private val spaces = Regex("\\s+")
    private val headings = Regex("<(h1|h2)\\b[^>]*>(.*?)</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private const val MAX_VISIBLE_REGION_TEXT = 4_096
    private const val MAX_CODE_LENGTH = 128

    fun classify(code: Int, contentType: String, body: String, locationHost: String = "",
        complete: Boolean = true): RegionalStatus = when {
        code == RegionalHttpStatus.AUTH_REQUIRED -> RegionalStatus.AUTH_REQUIRED
        code == RegionalHttpStatus.RATE_LIMITED -> RegionalStatus.RATE_LIMITED
        code in RegionalHttpStatus.redirect && locationHost.lowercase(Locale.ROOT) in loginHosts ->
            RegionalStatus.AUTH_REQUIRED
        code !in RegionalHttpStatus.success && code !in RegionalHttpStatus.denial -> RegionalStatus.UNKNOWN
        contentType.substringBefore(';').trim().lowercase(Locale.ROOT) == "application/json" ->
            json(body, complete, code)
        else -> visible(code, body, complete)
    }

    private fun visible(code: Int, body: String, complete: Boolean): RegionalStatus {
        val markup = RegionalMarkup.removeHidden(body.replace(comments, " ").replace(ignored, " "))
        val clean = markup.replace(tags, " ")
            .replace("&nbsp;", " ").replace("&#39;", "'")
            .lowercase(Locale.ROOT).replace(spaces, " ").trim()
        return when {
            complete && challengePhrases.any(clean::contains) -> RegionalStatus.CHALLENGE
            complete && clean.length <= MAX_VISIBLE_REGION_TEXT && regionPhrases.any(clean::contains) &&
                (code in RegionalHttpStatus.denial || regionHeading(markup)) ->
                RegionalStatus.REGION_RESTRICTED
            code in RegionalHttpStatus.success -> RegionalStatus.WEB_REACHABLE
            else -> RegionalStatus.UNKNOWN
        }
    }

    private fun regionHeading(body: String): Boolean = headings.findAll(
        body.replace(comments, " ").replace(ignored, " ")
    ).any { match ->
        val heading = match.groupValues[2].replace(tags, " ").lowercase(Locale.ROOT)
        regionPhrases.any(heading::contains) && !heading.trim().endsWith('?')
    }

    private fun json(body: String, complete: Boolean, code: Int): RegionalStatus {
        if (!complete) return RegionalStatus.UNKNOWN
        return try {
            val reader = StrictJsonReader(StringReader(body))
            var error = false
            var errorCode: String? = null
            reader.objectFields { name ->
                if (name == "error" && reader.nextType == '{') {
                    error = true
                    reader.objectFields { field ->
                        if (field == "code" && reader.nextType == '"') errorCode = reader.string(MAX_CODE_LENGTH)
                        else reader.skipValue()
                    }
                } else reader.skipValue()
            }
            reader.finish()
            when {
                errorCode?.lowercase(Locale.ROOT) in regionCodes -> RegionalStatus.REGION_RESTRICTED
                error -> RegionalStatus.UNKNOWN
                code in RegionalHttpStatus.success -> RegionalStatus.WEB_REACHABLE
                else -> RegionalStatus.UNKNOWN
            }
        } catch (_: IllegalArgumentException) { RegionalStatus.UNKNOWN }
    }
}
