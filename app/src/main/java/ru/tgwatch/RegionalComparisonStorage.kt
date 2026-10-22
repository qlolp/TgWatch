package ru.tgwatch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class RegionalComparisonStorage(file: File, io: HistoryFileOperations = SystemHistoryFileOperations) {
    private val storage = RegionalMetadataFile(file, io)
    fun read(): List<RegionalComparisonPair> = storage.read(RegionalComparisonCodec.MAX_BYTES)
        ?.let(RegionalComparisonCodec::decode) ?: emptyList()
    fun write(pairs: List<RegionalComparisonPair>) =
        storage.write(RegionalComparisonCodec.encode(pairs), RegionalComparisonCodec.MAX_BYTES)
}

/** Strict private format, separate from all portable backup and automation schemas. */
object RegionalComparisonCodec {
    const val MAX_PAIRS = 10
    const val MAX_BYTES = 64 * 1024
    private const val HEADER = "TGAB1"

    fun encode(pairs: List<RegionalComparisonPair>): ByteArray {
        require(pairs.size <= MAX_PAIRS)
        pairs.forEach(::validate)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUTF(HEADER)
            output.writeInt(pairs.size)
            pairs.forEach { pair -> writeSide(output, pair.a); writeSide(output, pair.b) }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): List<RegionalComparisonPair> {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readUTF() == HEADER) { "Unsupported comparison data" }
            val count = input.readInt()
            require(count in 0..MAX_PAIRS)
            val pairs = List(count) { RegionalComparisonPair(readSide(input), readSide(input)) }
            require(input.read() == -1) { "Trailing comparison data" }
            pairs.forEach(::validate)
            pairs
        }
    }

    fun validate(pair: RegionalComparisonPair) {
        RegionalComparisonRules.snapshot(pair.a)
        RegionalComparisonRules.snapshot(pair.b)
        require(pair.b.at >= pair.a.at && pair.a.readings.keys == pair.b.readings.keys)
    }

    private fun writeSide(output: DataOutputStream, side: RegionalComparisonSnapshot) {
        output.writeUTF(side.label)
        output.writeLong(side.at)
        output.writeUTF(side.connection.token)
        output.writeUTF(side.connection.route.name)
        output.writeUTF(side.connection.country ?: "-")
        output.writeLong(side.connection.countryAt ?: -1)
        output.writeInt(side.readings.size)
        side.readings.toSortedMap(compareBy { it.ordinal }).forEach { (service, row) ->
            output.writeUTF(service.wire)
            output.writeBoolean(row != null)
            if (row != null) output.writeUTF(RegionalJournalCodec.encode(listOf(row)))
        }
    }

    private fun readSide(input: DataInputStream): RegionalComparisonSnapshot {
        val label = input.readUTF()
        val at = input.readLong()
        val scope = input.readUTF()
        val route = RegionalRoute.valueOf(input.readUTF())
        val country = input.readUTF().takeUnless { it == "-" }
        val countryAt = input.readLong().takeUnless { it == -1L }
        val count = input.readInt()
        require(count in 1..RegionalService.entries.size)
        val readings = linkedMapOf<RegionalService, RegionalJournalEntry?>()
        repeat(count) {
            val service = RegionalService.fromWire(input.readUTF()) ?: error("Unknown comparison service")
            require(!readings.containsKey(service)) { "Duplicate comparison service" }
            val present = input.readUnsignedByte()
            require(present in 0..1) { "Invalid comparison presence marker" }
            readings[service] = if (present == 1) RegionalJournalCodec.decode(input.readUTF()).single() else null
        }
        return RegionalComparisonSnapshot(label, at, RegionalConnection(scope, route, country, countryAt), readings)
    }
}
