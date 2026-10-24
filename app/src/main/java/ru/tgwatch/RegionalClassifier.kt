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
    private const val SERVICE =
        "(?:this service|the service|gemini|youtube|notebook|notebooklm|gemini notebook|antigravity|chatgpt)"
    private val englishError = Regex("^(?:$SERVICE\\s+)?(?:is\\s+)?(?:currently\\s+)?" +
        "(?:not available|not supported|unavailable|isn't available|isn't supported) in your (?:country|region)" +
        "(?: yet| at this time| right now)?[.!]?$")
    private val russianError = Regex("^(?:(?:gemini|youtube|notebook|gemini notebook|antigravity|chatgpt|" +
        "сервис|этот сервис)\\s+)?(?:пока\\s+)?(?:недоступен|недоступно|не поддерживается) в " +
        "(?:вашей стране|вашем регионе)[.!]?$")
    private val challengePhrases = listOf("verify you are human", "unusual traffic", "checking your browser",
        "подтвердите, что вы человек", "подозрительный трафик")
    private val ignored = Regex(
        "<(script|style|head|template|noscript|pre|code|blockquote|q|article)\\b[^>]*>(?:.*?</\\1\\s*>|.*\\z)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val comments = Regex("<!--.*?(?:-->|$)", RegexOption.DOT_MATCHES_ALL)
    private val tags = Regex("<[^>]*>")
    private val spaces = Regex("\\s+")
    private val rendering = Regex("<(?:script|style|link)\\b", RegexOption.IGNORE_CASE)
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
        val uncommented = body.replace(comments, " ")
        val markup = RegionalMarkup.removeHidden(uncommented.replace(ignored, " "))
        val clean = text(markup)
        val regionEvidence = complete && regionEvidence(code, markup, clean)
        return when {
            complete && challengePhrases.any(clean::contains) -> RegionalStatus.CHALLENGE
            regionEvidence && rendering.containsMatchIn(uncommented) -> RegionalStatus.UNKNOWN
            regionEvidence -> RegionalStatus.REGION_RESTRICTED
            code in RegionalHttpStatus.success -> RegionalStatus.WEB_REACHABLE
            else -> RegionalStatus.UNKNOWN
        }
    }

    private fun text(body: String): String = body.replace(tags, " ").replace("&nbsp;", " ")
        .replace("&#39;", "'").replace('’', '\'').lowercase(Locale.ROOT).replace(spaces, " ").trim()

    private fun directError(message: String): Boolean = englishError.matches(message) || russianError.matches(message)

    private fun regionEvidence(code: Int, markup: String, clean: String): Boolean =
        clean.length <= MAX_VISIBLE_REGION_TEXT && (headings.findAll(markup).any {
            directError(text(it.groupValues[2]))
        } || code in RegionalHttpStatus.denial && directError(clean))

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
