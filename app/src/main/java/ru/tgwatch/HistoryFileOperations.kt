package ru.tgwatch

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** The three durable boundaries of a journal replacement. */
interface HistoryFileOperations {
    fun syncFile(descriptor: FileDescriptor)
    fun replace(source: File, destination: File)
    fun syncDirectory(directory: File)
}

object SystemHistoryFileOperations : HistoryFileOperations {
    override fun syncFile(descriptor: FileDescriptor) = descriptor.sync()
    override fun replace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // Same-filesystem rename replaces atomically on Android/Linux; never delete first.
            check(source.renameTo(destination)) { "Cannot atomically replace history" }
        }
    }
    override fun syncDirectory(directory: File) {
        if (System.getProperty("java.vm.name") == "Dalvik") {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        } else {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
