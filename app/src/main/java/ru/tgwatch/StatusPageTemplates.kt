package ru.tgwatch

import ru.tgwatch.probe.HttpProbeCondition

/**
 * One-tap HTTPS presets for custom HTTPS goals ([TargetId.isCustom]).
 *
 * Each entry matches a public status-page JSON document with a top-level or
 * `status.indicator` string field. Addresses are ordinary public HTTPS URLs
 * without credentials; conditions stay in private probe storage after save.
 */
internal object StatusPageTemplates {
    data class Template(
        val labelKey: TextKey,
        val url: String,
        val condition: HttpProbeCondition,
    )

    val all = listOf(
        Template(TextKey.TEMPLATE_DISCORD, "https://discordstatus.com/api/v2/status.json",
            HttpProbeCondition(jsonField = "status.indicator", expectedValue = "none")),
        Template(TextKey.TEMPLATE_GITHUB, "https://www.githubstatus.com/api/v2/status.json",
            HttpProbeCondition(jsonField = "status.indicator", expectedValue = "none")),
        Template(TextKey.TEMPLATE_CLOUDFLARE, "https://www.cloudflarestatus.com/api/v2/status.json",
            HttpProbeCondition(jsonField = "status.indicator", expectedValue = "none")),
        Template(TextKey.TEMPLATE_ATLASSIAN, "https://status.atlassian.com/api/v2/status.json",
            HttpProbeCondition(jsonField = "status.indicator", expectedValue = "none")),
        Template(TextKey.TEMPLATE_SPOTIFY, "https://spotifystatus.com/api/v2/status.json",
            HttpProbeCondition(jsonField = "status.indicator", expectedValue = "none")),
        Template(TextKey.TEMPLATE_OPENAI, "https://status.openai.com/api/v2/status.json",
            HttpProbeCondition(jsonField = "status.indicator", expectedValue = "none")),
    )
}
