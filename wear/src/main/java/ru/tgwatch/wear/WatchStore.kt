package ru.tgwatch.wear

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Public, fixed-field local cache only. Never reads the phone's preferences or history. */
internal class WatchStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "watch-result-v1"))
    fun load(): WatchSnapshot? = runCatching {
        require(file.baseFile.length() in 1..256)
        DataInputStream(file.openRead()).use { input ->
            require(input.readInt() == 0x54475731)
            val status = input.readUTF()
            val wall = input.readLong()
            val elapsed = input.readLong()
            val latency = input.readLong()
            val revision = input.readLong()
            val boot = input.readLong()
            require(input.read() == -1 && latency >= -1)
            WatchSnapshot(status, wall, elapsed, latency.takeIf { it >= 0 }, revision, boot)
        }
    }.getOrNull()
    fun save(snapshot: WatchSnapshot) {
        val stream = file.startWrite()
        try {
            DataOutputStream(stream).apply {
                writeInt(0x54475731)
                writeUTF(snapshot.status)
                writeLong(snapshot.checkedAtMs)
                writeLong(snapshot.checkedElapsedMs)
                writeLong(snapshot.latencyMs ?: -1)
                writeLong(snapshot.revision)
                writeLong(snapshot.bootSession)
                flush()
            }
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
