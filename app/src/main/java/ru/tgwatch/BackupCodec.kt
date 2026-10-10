package ru.tgwatch

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object BackupCodec {
    const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
    const val MAX_OBSERVATIONS = 65_000
    const val MAX_LOG_LINES = 150
    const val MAX_LOG_LINE_CHARS = 4096
    private val rootKeys = setOf("formatVersion", "createdAt", "settings", "observations", "log")
    private val rowKeys = setOf("at", "until", "kind", "latencyMs", "clockEpoch")
    private val booleanKeys = setOf("keep_awake", "vibrate", "vibrate_partial", "vibrate_offline",
        "vibrate_recovery", "notify_recovery", "event_sound", "quiet_hours", "notify_outage",
        "notify_partial", "sound_outage", "sound_partial", "sound_recovery")
    private val integerRanges = mapOf("interval_sec" to 10..120, "quiet_start_hour" to 0..23,
        "quiet_end_hour" to 0..23, "quiet_start_minute" to 0..1439, "quiet_end_minute" to 0..1439)
    private val profiles = setOf("ECONOMY", "BALANCED", "FREQUENT", "CUSTOM")
    private val patterns = setOf("STANDARD", "SHORT", "LONG")
    private val maxObjectKeys = maxOf(rootKeys.size, rowKeys.size, booleanKeys.size + integerRanges.size + 2)

    fun encode(snapshot: BackupSnapshot): ByteArray = checked {
        validate(snapshot)
        val rows = JSONArray()
        snapshot.observations.forEach { row -> rows.put(JSONObject().put("at", row.at)
            .put("until", row.until).put("kind", row.kind).put("latencyMs", row.latencyMs)
            .put("clockEpoch", row.clockEpoch)) }
        val settings = JSONObject()
        snapshot.settings.forEach { (key, value) -> settings.put(key, value) }
        val log = JSONArray()
        snapshot.log.forEach { log.put(it) }
        val text = JSONObject().put("formatVersion", 1).put("createdAt", snapshot.createdAt)
            .put("settings", settings).put("observations", rows).put("log", log).toString()
        require(text.length <= MAX_PLAINTEXT_BYTES)
        text.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_PLAINTEXT_BYTES) }
    }

    fun decode(bytes: ByteArray): BackupSnapshot = checked {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        // Android's JSON parser accepts coercions and non-JSON syntax. Validate before it allocates
        // its object tree, and bound nesting before any recursive platform parsing.
        JsonPreflight(text).check()
        val root = JSONObject(text)
        require(keys(root) == rootKeys && integral(root.get("formatVersion")) == 1L)
        val createdAt = integral(root.get("createdAt"))
        val jsonSettings = root.get("settings") as? JSONObject ?: invalid()
        val settings = linkedMapOf<String, Any>()
        for (key in keys(jsonSettings)) {
            val value = jsonSettings.get(key)
            settings[key] = if (key in integerRanges) {
                val number = integral(value)
                require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                number.toInt()
            } else value
        }
        validateSettings(settings)
        val rows = root.get("observations") as? JSONArray ?: invalid()
        require(rows.length() <= MAX_OBSERVATIONS)
        val observations = ArrayList<Observation>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.get(i) as? JSONObject ?: invalid()
            require(keys(row) == rowKeys)
            observations += Observation(integral(row.get("at")), integral(row.get("until")),
                row.get("kind") as? String ?: invalid(), integral(row.get("latencyMs")), integral(row.get("clockEpoch")))
        }
        val lines = root.get("log") as? JSONArray ?: invalid()
        require(lines.length() <= MAX_LOG_LINES)
        val log = (0 until lines.length()).map { lines.get(it) as? String ?: invalid() }
        BackupSnapshot(createdAt, settings, observations, log).also { validate(it) }
    }

    private fun validate(snapshot: BackupSnapshot) {
        require(snapshot.createdAt >= 0)
        validateSettings(snapshot.settings)
        require(snapshot.observations.size <= MAX_OBSERVATIONS && snapshot.log.size <= MAX_LOG_LINES)
        snapshot.observations.forEach {
            require(it.at >= 0 && it.until > it.at && it.until - it.at <= 600_000L)
            require(it.kind in Timeline.kinds && it.latencyMs >= -1 && it.clockEpoch >= 0)
        }
        snapshot.log.forEach { require(it.length <= MAX_LOG_LINE_CHARS); validUnicode(it) }
    }

    private fun validateSettings(settings: Map<String, Any>) {
        settings.forEach { (key, value) ->
            when {
                key in booleanKeys -> require(value is Boolean)
                key in integerRanges -> require(value is Int && value in integerRanges.getValue(key))
                key == "power_profile" -> require(value is String && value in profiles)
                key == "vibration_pattern" -> require(value is String && value in patterns)
                else -> invalid()
            }
        }
    }

    private fun integral(value: Any): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> invalid()
    }

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid backup")
    private inline fun <T> checked(block: () -> T): T = try { block() } catch (_: Exception) { invalid() }

    private fun validUnicode(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c.isHighSurrogate()) require(i < text.length && text[i++].isLowSurrogate())
            else require(!c.isLowSurrogate())
        }
    }

    /** Strict JSON grammar, integral Long literals, duplicate-key and resource bounds. */
    private class JsonPreflight(private val text: String) {
        private var at = 0
        private var objects = 0
        private var arrays = 0
        private var values = 0
        fun check() { value(0); whitespace(); require(at == text.length) }
        private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
        private fun take(c: Char): Boolean { whitespace(); return if (at < text.length && text[at] == c) { at++; true } else false }
        private fun value(depth: Int) {
            whitespace()
            require(at < text.length && depth <= 8)
            require(++values <= MAX_OBSERVATIONS * 6 + MAX_LOG_LINES + 100)
            when (text[at]) {
                '{' -> {
                    require(++objects <= MAX_OBSERVATIONS + 2)
                    at++
                    val names = HashSet<String>()
                    if (!take('}')) do {
                        whitespace()
                        require(names.add(string()) && names.size <= maxObjectKeys && take(':'))
                        value(depth + 1)
                        if (take('}')) return
                        require(take(','))
                    } while (true)
                }
                '[' -> {
                    require(++arrays <= 2)
                    at++
                    var count = 0
                    if (!take(']')) do {
                        require(++count <= MAX_OBSERVATIONS)
                        value(depth + 1)
                        if (take(']')) return
                        require(take(','))
                    } while (true)
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }
        private fun literal(value: String) { require(text.startsWith(value, at)); at += value.length }
        private fun number() {
            val start = at
            if (text[at] == '-') at++
            require(at < text.length && text[at] in '0'..'9')
            if (text[at] == '0') at++ else while (at < text.length && text[at] in '0'..'9') {
                at++; require(at - start <= 20)
            }
            require(text.substring(start, at).toLongOrNull() != null)
        }
        private fun string(): String {
            require(at < text.length && text[at++] == '"')
            val result = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') return result.toString().also { validUnicode(it) }
                require(c >= ' ')
                if (c != '\\') result.append(c) else {
                    require(at < text.length)
                    when (val escape = text[at++]) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(at + 4 <= text.length)
                            val hex = text.substring(at, at + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            result.append(hex.toInt(16).toChar()); at += 4
                        }
                        else -> invalid()
                    }
                }
                require(result.length <= MAX_LOG_LINE_CHARS)
            }
            invalid()
        }
    }
}
