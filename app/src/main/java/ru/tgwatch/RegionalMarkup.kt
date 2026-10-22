package ru.tgwatch

import java.util.Locale

/** Discard explicitly hidden subtrees before examining small public error documents. */
internal object RegionalMarkup {
    private const val MAX_DEPTH = 64
    private const val ATTRIBUTES_GROUP = 3
    private val tokens = Regex("<(/?)([a-zA-Z][a-zA-Z0-9:-]*)([^>]*)>")
    private val hidden = Regex("(?:^|\\s)hidden(?:\\s|=|/|$)", RegexOption.IGNORE_CASE)
    private val ariaHidden = Regex("(?:^|\\s)aria-hidden\\s*=\\s*[\"']?true(?:[\"'\\s/]|$)",
        RegexOption.IGNORE_CASE)
    private val style = Regex("(?:^|\\s)style\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val invisibleStyle = Regex("(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)", RegexOption.IGNORE_CASE)
    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr")

    fun removeHidden(body: String): String {
        val stack = mutableListOf<Pair<String, Boolean>>()
        val result = StringBuilder()
        var end = 0
        for (match in tokens.findAll(body)) {
            val parentHidden = stack.lastOrNull()?.second == true
            if (!parentHidden) result.append(body.substring(end, match.range.first))
            val name = match.groupValues[2].lowercase(Locale.ROOT)
            if (match.groupValues[1] == "/") {
                val index = stack.indexOfLast { it.first == name }
                if (index >= 0) stack.subList(index, stack.size).clear()
                if (!parentHidden) result.append(match.value)
            } else {
                val attributes = match.groupValues[ATTRIBUTES_GROUP]
                val excluded = parentHidden || invisible(attributes)
                if (!excluded) result.append(match.value)
                if (name !in voidTags && !attributes.trimEnd().endsWith('/')) stack.add(name to excluded)
                if (stack.size > MAX_DEPTH) return ""
            }
            end = match.range.last + 1
        }
        if (stack.lastOrNull()?.second != true) result.append(body.substring(end))
        return result.toString()
    }

    private fun invisible(attributes: String): Boolean = hidden.containsMatchIn(attributes) ||
        ariaHidden.containsMatchIn(attributes) || style.findAll(attributes).any { match ->
            invisibleStyle.containsMatchIn(match.groupValues.drop(1).joinToString(""))
        }
}
