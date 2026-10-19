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
