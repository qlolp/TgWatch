package ru.tgwatch

import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter

internal object TargetBackupWriter {
    fun write(snapshot: TargetBackupSnapshot): ByteArray {
        val out = TargetJsonOutput()
        out.raw("{\"formatVersion\":${TargetBackupCodec.FORMAT_VERSION},")
        out.raw("\"createdAt\":${snapshot.createdAtMs},\"settings\":")
        settings(out, snapshot.globalSettings)
        out.raw(",\"targets\":[")
        snapshot.targets.forEachIndexed { index, target ->
            if (index > 0) out.raw(",")
            target(out, target)
        }
        out.raw("],\"observations\":[")
        observations(out, snapshot)
        out.raw("],\"log\":[")
        snapshot.log.forEachIndexed { index, line ->
            if (index > 0) out.raw(",")
            out.string(line)
        }
        out.raw("]}")
        return out.bytes()
    }

    private fun settings(out: TargetJsonOutput, settings: Map<String, Any>) {
        out.raw("{")
        settings.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
            if (index > 0) out.raw(",")
            out.string(key)
            out.raw(":")
            if (value is String) out.string(value) else out.raw(value.toString())
        }
        out.raw("}")
    }

    private fun target(out: TargetJsonOutput, target: TargetDefinition) {
        out.raw("{\"id\":")
        out.string(target.id.wire)
        out.raw(",\"enabled\":${target.enabled},\"intervalSec\":${target.intervalSec},")
        out.raw("\"requiresEndpoint\":${target.requiresEndpoint},\"alerts\":{")
        val alerts = target.alerts
        out.raw("\"notifyOutage\":${alerts.notifyOutage},\"notifyRecovery\":${alerts.notifyRecovery},")
        out.raw("\"notifySlow\":${alerts.notifySlow},\"slowThresholdMs\":${alerts.slowThresholdMs}}}")
    }

    private fun observations(out: TargetJsonOutput, snapshot: TargetBackupSnapshot) {
        var first = true
        snapshot.targets.forEach { target -> snapshot.observations.getValue(target.id).forEach { observation ->
            if (!first) out.raw(",")
            first = false
            val row = observation.row
            out.raw("[")
            out.string(target.id.wire)
            out.raw(",${row.at},${row.until},")
            out.string(row.kind)
            out.raw(",${row.latencyMs},${row.clockEpoch},${observation.endpointRevision}]")
        } }
    }
}

/** Output is capped before ByteArrayOutputStream can grow beyond the archive's plaintext budget. */
private class TargetJsonOutput {
    private val output = CappedJsonBuffer()
    private val writer = OutputStreamWriter(output, Charsets.UTF_8)
    fun raw(value: String) { writer.write(value) }
    fun string(value: String) {
        writer.write('"'.code)
        value.forEach { c -> when (c) {
            '"' -> writer.write("\\\"")
            '\\' -> writer.write("\\\\")
            '\b' -> writer.write("\\b")
            '\u000c' -> writer.write("\\f")
            '\n' -> writer.write("\\n")
            '\r' -> writer.write("\\r")
            '\t' -> writer.write("\\t")
            else -> if (c.code < CONTROL_END) writer.write("\\u" + c.code.toString(HEX_RADIX).padStart(HEX_CHARS, '0'))
                else writer.write(c.code)
        } }
        writer.write('"'.code)
    }
    fun bytes(): ByteArray { writer.flush(); return output.toByteArray() }
    private companion object {
        const val CONTROL_END = 0x20
        const val HEX_RADIX = 16
        const val HEX_CHARS = 4
    }
}

private class CappedJsonBuffer : ByteArrayOutputStream(INITIAL_BYTES) {
    override fun write(value: Int) {
        require(count < TargetBackupCodec.MAX_PLAINTEXT_BYTES)
        super.write(value)
    }
    override fun write(value: ByteArray, offset: Int, length: Int) {
        require(length <= TargetBackupCodec.MAX_PLAINTEXT_BYTES - count)
        super.write(value, offset, length)
    }
    private companion object { const val INITIAL_BYTES = 8192 }
}
