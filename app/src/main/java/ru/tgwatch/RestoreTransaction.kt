package ru.tgwatch

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Durable, replayable application of a previously validated snapshot. */
class RestoreTransaction(private val pending: File, private val maxBytes: Int = 8 * 1024 * 1024) {
    @Synchronized fun stage(validatedPayload: ByteArray) {
        require(validatedPayload.isNotEmpty() && validatedPayload.size <= maxBytes)
        check(!pending.exists()) { "Pending restore must be completed first" }
        val directory = pending.absoluteFile.parentFile!!
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, pending.name + ".tmp")
        try {
            FileOutputStream(temporary).use { it.write(validatedPayload); it.fd.sync() }
            try {
                Files.move(temporary.toPath(), pending.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                check(temporary.renameTo(pending)) { "Could not persist restore journal" }
            }
            syncParent(pending)
        } finally { temporary.delete() }
    }

    /** Keep the old journal until an authoritative, validated replacement is durable. */
    @Synchronized fun migrateLegacy(
        legacy: File,
        convert: (ByteArray) -> ByteArray,
        validate: (ByteArray) -> Unit,
    ): Boolean {
        if (!legacy.exists()) return false
        if (pending.exists()) {
            validate(readPayload(pending))
            syncParent(pending)
        } else {
            val converted = convert(readPayload(legacy))
            require(converted.isNotEmpty() && converted.size <= maxBytes)
            validate(converted)
            stage(converted)
        }
        check(legacy.delete()) { "Could not finish legacy restore migration" }
        syncParent(legacy)
        return true
    }

    @Synchronized fun recover(apply: (ByteArray) -> Unit): Boolean {
        if (!pending.exists()) return false
        apply(readPayload(pending))
        check(pending.delete()) { "Could not finish restore journal" }
        syncParent(pending)
        return true
    }

    private fun readPayload(file: File): ByteArray =
        file.inputStream().use { BoundedInput.read(it, maxBytes) }.also { require(it.isNotEmpty()) }

    private fun syncParent(file: File) {
        FileChannel.open(file.absoluteFile.parentFile!!.toPath(), StandardOpenOption.READ).use {
            it.force(true)
        }
    }
}

/** Read an untrusted document without allocating beyond its declared byte budget. */
object BoundedInput {
    fun read(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                val one = input.read()
                if (one < 0) return output.toByteArray()
                require(output.size() < maxBytes) { "Document exceeds size limit" }
                output.write(one)
            } else {
                require(count <= maxBytes - output.size()) { "Document exceeds size limit" }
                output.write(buffer, 0, count)
            }
        }
    }
}
