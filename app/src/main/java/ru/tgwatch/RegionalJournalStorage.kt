package ru.tgwatch

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One owner serializes operations. A failed pre-rename write never replaces the old durable file. */
class RegionalJournalStorage(private val file: File,
    io: HistoryFileOperations = SystemHistoryFileOperations) {
    private val storage = RegionalMetadataFile(file, io)
    fun read(): List<RegionalJournalEntry> {
        val bytes = storage.read(RegionalJournalCodec.MAX_BYTES) ?: return emptyList()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return RegionalJournalCodec.decode(text)
    }

    fun write(rows: List<RegionalJournalEntry>) {
        val bytes = RegionalJournalCodec.encode(rows).toByteArray(Charsets.UTF_8)
        storage.write(bytes, RegionalJournalCodec.MAX_BYTES)
    }
}
