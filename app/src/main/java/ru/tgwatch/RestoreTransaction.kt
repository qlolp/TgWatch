package ru.tgwatch

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        } finally { temporary.delete() }
    }

    @Synchronized fun recover(apply: (ByteArray) -> Unit): Boolean {
        if (!pending.exists()) return false
        val payload = pending.inputStream().use { BoundedInput.read(it, maxBytes) }
        require(payload.isNotEmpty())
        apply(payload)
        check(pending.delete()) { "Could not finish restore journal" }
        return true
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
