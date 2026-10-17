package ru.tgwatch

import java.io.Reader

/** Strict, bounded-depth pull reader. Callers validate schema/counts while consuming one value at a time. */
class StrictJsonReader(source: Reader, private val maxDepth: Int = DEFAULT_MAX_DEPTH) {
    private val tokens = JsonTokens(source)
    private var depth = 0
    val nextType: Char? get() = tokens.peek().takeIf { it >= 0 }?.toChar()

    fun objectFields(read: (String) -> Unit) {
        enter('{')
        val names = hashSetOf<String>()
        if (!tokens.take('}')) {
            do {
                val name = string()
                require(names.add(name)) { "Duplicate JSON key" }
                tokens.expect(':')
                read(name)
                if (tokens.take('}')) break
                tokens.expect(',')
            } while (true)
        }
        depth--
    }

    fun arrayValues(read: (Int) -> Unit) {
        enter('[')
        var index = 0
        if (!tokens.take(']')) {
            do {
                read(index++)
                if (tokens.take(']')) break
                tokens.expect(',')
            } while (true)
        }
        depth--
    }

    fun string(maxChars: Int = DEFAULT_MAX_STRING): String = JsonStrings.read(tokens, maxChars)
    fun longValue(): Long = tokens.number(integral = true).toLongOrNull() ?: errorNumber()
    fun booleanValue(): Boolean = when (tokens.peek().toChar()) {
        't' -> { tokens.literal("true"); true }
        'f' -> { tokens.literal("false"); false }
        else -> throw IllegalArgumentException("Invalid JSON boolean")
    }
    fun nullValue() = tokens.literal("null")
    fun finish() { require(tokens.peek() < 0) { "Trailing JSON data" } }

    fun skipValue() {
        when (nextType) {
            '{' -> objectFields { skipValue() }
            '[' -> arrayValues { skipValue() }
            '"' -> string()
            't', 'f' -> booleanValue()
            'n' -> nullValue()
            '-', in '0'..'9' -> tokens.number(integral = false)
            else -> throw IllegalArgumentException("Invalid JSON value")
        }
    }

    private fun enter(character: Char) {
        require(++depth <= maxDepth) { "JSON nesting limit" }
        tokens.expect(character)
    }

    private fun errorNumber(): Nothing = throw IllegalArgumentException("Invalid JSON integer")

    private companion object {
        const val DEFAULT_MAX_DEPTH = 10
        const val DEFAULT_MAX_STRING = 65_536
    }
}

private class JsonTokens(private val source: Reader) {
    private var next = source.read()
    fun peek(): Int {
        while (next >= 0 && next.toChar() in " \t\r\n") readRaw()
        return next
    }
    fun readRaw(): Int = next.also { next = source.read() }
    fun take(character: Char): Boolean = if (peek() == character.code) { readRaw(); true } else false
    fun expect(character: Char) { require(take(character)) { "Invalid JSON structure" } }
    fun literal(text: String) {
        peek()
        text.forEach { require(readRaw() == it.code) { "Invalid JSON literal" } }
    }
    fun number(integral: Boolean): String {
        peek()
        val token = StringBuilder()
        while (next >= 0 && next.toChar() in "-+0123456789.eE") {
            token.append(readRaw().toChar())
            require(token.length <= MAX_NUMBER_CHARS) { "JSON number limit" }
        }
        return token.toString().also { require((if (integral) INTEGER else NUMBER).matches(it)) }
    }
    private companion object {
        const val MAX_NUMBER_CHARS = 128
        val INTEGER = Regex("-?(0|[1-9][0-9]*)")
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}

private object JsonStrings {
    private const val CONTROL_END = 0x20
    private const val HEX_DIGITS = 4
    private const val HEX_RADIX = 16
    fun read(tokens: JsonTokens, maxChars: Int): String {
        require(maxChars >= 0)
        tokens.expect('"')
        val value = StringBuilder()
        while (true) {
            val c = tokens.readRaw()
            require(c >= CONTROL_END) { "Invalid JSON string" }
            if (c == '"'.code) break
            value.append(if (c == '\\'.code) escape(tokens) else c.toChar())
            require(value.length <= maxChars) { "JSON string limit" }
        }
        return value.toString().also(::validateUnicode)
    }
    private fun escape(tokens: JsonTokens): Char = when (val code = tokens.readRaw().toChar()) {
        '"', '\\', '/' -> code
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> unicode(tokens)
        else -> throw IllegalArgumentException("Invalid JSON escape")
    }
    private fun unicode(tokens: JsonTokens): Char {
        val digits = StringBuilder()
        repeat(HEX_DIGITS) {
            val c = tokens.readRaw().toChar()
            require(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') { "Invalid JSON Unicode escape" }
            digits.append(c)
        }
        return digits.toString().toInt(HEX_RADIX).toChar()
    }
    private fun validateUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (c.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate())
            else require(!c.isLowSurrogate())
        }
    }
}
