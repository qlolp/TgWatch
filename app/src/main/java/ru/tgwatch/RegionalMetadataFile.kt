package ru.tgwatch

import java.io.File
import java.io.FileOutputStream

/** Bounded reads and durable atomic replacement shared by the two device-local metadata codecs. */
internal class RegionalMetadataFile(private val file: File,
    private val io: HistoryFileOperations = SystemHistoryFileOperations) {
    fun read(maxBytes: Int): ByteArray? {
        require(maxBytes in 1..MAX_BYTES)
        if (!file.exists()) return null
        require(file.length() <= maxBytes) { "Regional metadata too large" }
        val bytes = ByteArray(maxBytes + 1)
        var count = 0
        file.inputStream().use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
        }
        require(count <= maxBytes) { "Regional metadata grew past its limit" }
        return bytes.copyOf(count)
    }

    fun write(bytes: ByteArray, maxBytes: Int) {
        require(maxBytes in 1..MAX_BYTES && bytes.size <= maxBytes)
        val directory = file.absoluteFile.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create regional storage" }
        val temporary = File(directory, file.name + ".tmp")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); io.syncFile(stream.fd) }
            io.replace(temporary, file)
            io.syncDirectory(directory)
        } finally { temporary.delete() }
    }

    private companion object { const val MAX_BYTES = 512 * 1024 }
}
