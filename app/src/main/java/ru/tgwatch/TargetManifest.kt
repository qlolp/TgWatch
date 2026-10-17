package ru.tgwatch

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object TargetManifest {
    private const val NAME = "active-generation"
    private const val PREFIX = "TGWTARGET1\n"
    private const val MAX_BYTES = 64L
    private val generation = Regex("[a-f0-9]{32}")

    fun read(directory: File): String? {
        val file = File(directory, NAME)
        if (!file.exists()) return null
        if (file.length() > MAX_BYTES) throw IOException("Invalid target manifest")
        val text = file.readText(Charsets.US_ASCII)
        val name = text.removePrefix(PREFIX).removeSuffix("\n")
        if (text != PREFIX + name + "\n" || !generation.matches(name)) throw IOException("Invalid target manifest")
        return name
    }

    fun bytes(name: String) = (PREFIX + name + "\n").toByteArray(Charsets.US_ASCII)

    /** Recovery intentionally uses the healthy system adapter rather than the failed publication adapter. */
    fun restore(directory: File, previous: String?) {
        val manifest = File(directory, NAME)
        if (previous == null) {
            if (!manifest.delete()) throw IOException("Cannot roll back target manifest")
        } else {
            val rollback = File(directory, "$NAME.rollback")
            FileOutputStream(rollback).use { it.write(bytes(previous)); it.fd.sync() }
            SystemHistoryFileOperations.replace(rollback, manifest)
        }
        SystemHistoryFileOperations.syncDirectory(directory)
    }
}
