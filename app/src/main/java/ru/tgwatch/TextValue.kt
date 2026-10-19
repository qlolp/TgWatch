package ru.tgwatch

import java.util.Collections

/** Immutable semantic presentation data; neither Context nor language strings enter pure decisions. */
class TextValue(val key: TextKey, args: List<TextArgument> = emptyList()) {
    val args: List<TextArgument> = Collections.unmodifiableList(ArrayList(args))
    override fun equals(other: Any?): Boolean = other is TextValue && key == other.key && args == other.args
    override fun hashCode(): Int = HASH_FACTOR * key.hashCode() + args.hashCode()
    override fun toString(): String = "TextValue($key)"
    private companion object { const val HASH_FACTOR = 31 }
}

sealed interface TextArgument {
    data class Number(val value: Long) : TextArgument
    data class Decimal(val value: Double) : TextArgument
    data class Text(val value: String) : TextArgument
    data class Duration(val parts: DurationParts, val precise: Boolean = false) : TextArgument
    data class Value(val value: TextValue) : TextArgument
}

internal fun textOf(key: TextKey, vararg args: TextArgument) = TextValue(key, args.toList())
internal fun textLines(values: List<TextValue>) = TextValue(TextKey.LINES, values.map(TextArgument::Value))

internal fun textArgument(value: Any?): TextArgument = when (value) {
    is TextValue -> TextArgument.Value(value)
    is DurationParts -> TextArgument.Duration(value)
    is Int -> TextArgument.Number(value.toLong())
    is Long -> TextArgument.Number(value)
    else -> TextArgument.Text(value?.toString().orEmpty())
}
internal fun textLiteral(value: String) = textOf(TextKey.LITERAL, TextArgument.Text(value))
internal operator fun TextValue.plus(other: TextValue) = textOf(TextKey.CONCAT,
    TextArgument.Value(this), TextArgument.Value(other))
internal operator fun TextValue.plus(other: String) = this + textLiteral(other)
internal fun textJoin(values: List<TextValue>, separator: String): TextValue =
    values.reduceOrNull { result, value -> result + separator + value } ?: textLiteral("")
internal fun timeText(at: Long) = if (at <= 0L) textLiteral("—") else textOf(TextKey.TIME_OF_DAY,
    TextArgument.Number(at), TextArgument.Text(java.util.TimeZone.getDefault().id))
internal fun ageText(at: Long, now: Long) = when {
    at <= 0L -> textLiteral("—")
    now - at < JUST_NOW_MS -> textOf(TextKey.JUST_NOW)
    else -> textOf(TextKey.AGO, TextArgument.Duration(DurationParts.from(now - at)))
}

private const val JUST_NOW_MS = 2_000L

internal fun absoluteTimeText(at: Long, zone: java.util.TimeZone) = textOf(TextKey.ABSOLUTE_TIME,
    TextArgument.Number(at), TextArgument.Text(zone.id))
