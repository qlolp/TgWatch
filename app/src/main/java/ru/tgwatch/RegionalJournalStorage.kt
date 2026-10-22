package ru.tgwatch

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One owner serializes operations. A failed pre-rename write never replaces the old durable file. */
class RegionalJournalStorage(private val file: File,
    private val io: HistoryFileOperations = SystemHistoryFileOperations) {
    fun read(): List<RegionalJournalEntry> {
        if (!file.exists()) return emptyList()
        require(file.length() <= RegionalJournalCodec.MAX_BYTES) { "Regional journal too large" }
        val bytes = ByteArray(RegionalJournalCodec.MAX_BYTES + 1)
        var count = 0
        file.inputStream().use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
        }
        require(count <= RegionalJournalCodec.MAX_BYTES) { "Regional journal grew past its limit" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, count)).toString()
        return RegionalJournalCodec.decode(text)
    }

    fun write(rows: List<RegionalJournalEntry>) {
        val bytes = RegionalJournalCodec.encode(rows).toByteArray(Charsets.UTF_8)
        val directory = file.absoluteFile.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create regional storage" }
        val temporary = File(directory, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); io.syncFile(stream.fd) }
            io.replace(temporary, file)
            io.syncDirectory(directory)
        } finally { temporary.delete() }
    }
}
