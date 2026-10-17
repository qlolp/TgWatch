package ru.tgwatch

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction

/** No JSONObject/JSONArray over the observation array: each bounded tuple becomes one domain record. */
internal class TargetBackupReader(source: Reader) {
    constructor(bytes: ByteArray) : this(InputStreamReader(ByteArrayInputStream(bytes),
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)).buffered())
    private val reader = StrictJsonReader(source)

    fun read(): TargetBackupSnapshot {
        var version: Long? = null
        var createdAt: Long? = null
        var settings: Map<String, Any>? = null
        var targets: List<TargetDefinition>? = null
        var observations: Map<TargetId, List<TargetObservation>>? = null
        var log: List<String>? = null
        reader.objectFields { key -> when (key) {
            "formatVersion" -> version = reader.longValue()
            "createdAt" -> createdAt = reader.longValue()
            "settings" -> settings = settings()
            "targets" -> targets = targets()
            "observations" -> observations = observations()
            "log" -> log = log()
            else -> throw IllegalArgumentException("Unknown backup field")
        } }
        reader.finish()
        require(version == TargetBackupCodec.FORMAT_VERSION.toLong())
        val definitions = checkNotNull(targets)
        val ids = definitions.map { it.id }.toSet()
        val supplied = checkNotNull(observations)
        require(supplied.keys.all { it in ids })
        return TargetBackupSnapshot(checkNotNull(createdAt), checkNotNull(settings), definitions,
            definitions.associate { it.id to supplied[it.id].orEmpty() }, checkNotNull(log))
    }

    private fun settings(): Map<String, Any> = linkedMapOf<String, Any>().also { values ->
        reader.objectFields { key ->
            require(key in BackupCodec.settingKeys && key != TargetBackupCodec.LEGACY_ENDPOINT_KEY)
            val value: Any = when (reader.nextType) {
                '"' -> reader.string(BackupCodec.MAX_LOG_LINE_CHARS)
                't', 'f' -> reader.booleanValue()
                else -> reader.longValue().also {
                    require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                }.toInt()
            }
            values[key] = value
        }
    }

    private fun targets(): List<TargetDefinition> = buildList {
        val ids = hashSetOf<TargetId>()
        reader.arrayValues { index ->
            require(index < TargetId.entries.size)
            val target = target()
            require(ids.add(target.id))
            add(target)
        }
    }

    private fun target(): TargetDefinition {
        var id: TargetId? = null
        var enabled: Boolean? = null
        var interval: Int? = null
        var requiresEndpoint: Boolean? = null
        var alerts: TargetAlertSettings? = null
        reader.objectFields { key -> when (key) {
            "id" -> id = TargetId.fromWire(reader.string(MAX_ID_CHARS)).also { require(it != null) }
            "enabled" -> enabled = reader.booleanValue()
            "intervalSec" -> interval = reader.longValue().also {
                require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            }.toInt()
            "requiresEndpoint" -> requiresEndpoint = reader.booleanValue()
            "alerts" -> alerts = alerts()
            else -> throw IllegalArgumentException("Unknown target field")
        } }
        return TargetDefinition(checkNotNull(id), checkNotNull(enabled), checkNotNull(interval),
            checkNotNull(requiresEndpoint), checkNotNull(alerts))
    }

    private fun alerts(): TargetAlertSettings {
        var outage: Boolean? = null
        var recovery: Boolean? = null
        var slow: Boolean? = null
        var threshold: Long? = null
        reader.objectFields { key -> when (key) {
            "notifyOutage" -> outage = reader.booleanValue()
            "notifyRecovery" -> recovery = reader.booleanValue()
            "notifySlow" -> slow = reader.booleanValue()
            "slowThresholdMs" -> threshold = reader.longValue()
            else -> throw IllegalArgumentException("Unknown alert field")
        } }
        return TargetAlertSettings(checkNotNull(outage), checkNotNull(recovery),
            checkNotNull(slow), checkNotNull(threshold))
    }

    private fun observations(): Map<TargetId, List<TargetObservation>> {
        val rows = linkedMapOf<TargetId, MutableList<TargetObservation>>()
        reader.arrayValues { index ->
            require(index < TargetBackupCodec.MAX_OBSERVATIONS)
            val (id, observation) = tuple()
            val target = rows.getOrPut(id) { arrayListOf() }
            require(target.size < TargetBackupCodec.rowLimit(id))
            target.add(observation)
        }
        return rows
    }

    private fun tuple(): Pair<TargetId, TargetObservation> {
        var id: TargetId? = null
        var at = 0L
        var until = 0L
        var kind: String? = null
        var latency = 0L
        var epoch = 0L
        var endpoint = 0L
        var count = 0
        reader.arrayValues { index ->
            count++
            when (index) {
                ID -> id = TargetId.fromWire(reader.string(MAX_ID_CHARS)).also { require(it != null) }
                AT -> at = reader.longValue()
                UNTIL -> until = reader.longValue()
                KIND -> kind = reader.string(MAX_ID_CHARS)
                LATENCY -> latency = reader.longValue()
                EPOCH -> epoch = reader.longValue()
                ENDPOINT -> endpoint = reader.longValue()
                else -> throw IllegalArgumentException("Invalid observation tuple")
            }
        }
        require(count == TUPLE_FIELDS)
        val observation = TargetObservation(Observation(at, until, checkNotNull(kind), latency, epoch), endpoint)
        TargetBackupCodec.validateRow(observation)
        return checkNotNull(id) to observation
    }

    private fun log(): List<String> = buildList {
        reader.arrayValues { index ->
            require(index < BackupCodec.MAX_LOG_LINES)
            add(reader.string(BackupCodec.MAX_LOG_LINE_CHARS))
        }
    }

    private companion object {
        const val MAX_ID_CHARS = 16
        const val TUPLE_FIELDS = 7
        const val ID = 0
        const val AT = 1
        const val UNTIL = 2
        const val KIND = 3
        const val LATENCY = 4
        const val EPOCH = 5
        const val ENDPOINT = 6
    }
}
