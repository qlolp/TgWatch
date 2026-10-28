package ru.tgwatch

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Bounded record of uncaught exceptions, stored only on this device.
 *
 * A record contains the exception class, the crashing thread name and stack frames. Exception
 * messages are never stored: they can carry endpoints, tokens or document paths. The journal is
 * written at crash time, survives the process death, keeps the newest [MAX_RECORDS] records and is
 * exposed only through the explicit diagnostics document. Remote reporting, when separately
 * configured and consented to, sends one sanitized record to the user's own collector.
 */
@Suppress("TooManyFunctions") // One bounded format: storage, rotation and parsing primitives belong together.
object CrashJournal {
    private const val FILE = "crashes-v1.txt"
    private const val SEPARATOR = "\n\n"
    private const val HEADER = '#'
    private const val FIELD_SEPARATOR = '|'
    private const val HEADER_FIELDS = 3
    private const val FRAME_PREFIX = "  at "
    private const val MAX_BYTES = 32 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024
    private const val MAX_RECORDS = 5
    private const val MAX_FRAMES = 24
    private const val MAX_TIME_MS = 4_102_444_800_000L

    data class Record(val atMs: Long, val thread: String, val type: String, val frames: List<String>)
    data class Summary(val retained: Int, val last: Record?)

    @Volatile private var directory: File? = null
    @Volatile private var installed = false

    /** Installs the journal once; the previous handler still reports and terminates the process. */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val dir = runCatching {
            ctx.applicationContext.createDeviceProtectedStorageContext().filesDir
        }.getOrNull() ?: return
        directory = dir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(dir, thread, error, System.currentTimeMillis())
                RemoteCrashReporter.report(ctx.applicationContext,
                    format(thread, error, System.currentTimeMillis()))
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /**
     * Appends one record without reading or writing anything but the bounded journal file. A crash
     * handler must never throw: any storage failure is dropped after logging its class only.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun record(dir: File, thread: Thread, error: Throwable, now: Long) {
        try {
            val records = read(dir) + bounded(format(thread, error, now))
            write(dir, retained(records))
        } catch (e: Exception) {
            Log.w("TgWatch", "crash journal: ${e.javaClass.simpleName}")
        }
    }

    internal fun format(thread: Thread, error: Throwable, now: Long): Record = Record(
        atMs = now.coerceIn(0L, MAX_TIME_MS),
        thread = CrashMetadata.thread(thread.name),
        type = CrashMetadata.type(error.javaClass.name),
        frames = error.stackTrace.asSequence().take(MAX_FRAMES)
            .map(CrashMetadata::frame).toList())

    internal fun redacted(record: Record): Record = Record(record.atMs.coerceIn(0L, MAX_TIME_MS),
        CrashMetadata.thread(record.thread), CrashMetadata.type(record.type),
        record.frames.take(MAX_FRAMES).map(CrashMetadata::frame))

    internal fun read(dir: File): List<Record> {
        val file = File(dir, FILE)
        if (!file.isFile || file.length() > MAX_BYTES) return emptyList()
        return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            .split(SEPARATOR).mapNotNull(::parse).takeLast(MAX_RECORDS)
    }

    internal fun summary(dir: File): Summary {
        val records = read(dir)
        return Summary(records.size, records.lastOrNull())
    }

    /** Read-only view for the diagnostics document; any storage problem reads as "no records". */
    @Suppress("TooGenericExceptionCaught")
    fun summary(ctx: Context): Summary = try {
        val dir = directory ?: ctx.applicationContext.createDeviceProtectedStorageContext().filesDir
        summary(dir)
    } catch (e: Exception) {
        Log.w("TgWatch", "crash journal read: ${e.javaClass.simpleName}")
        Summary(0, null)
    }

    /** Removes every record; diagnostics export never needs them after the user cleared the log. */
    fun clear(ctx: Context) {
        val dir = directory ?: runCatching {
            ctx.applicationContext.createDeviceProtectedStorageContext().filesDir
        }.getOrNull() ?: return
        runCatching { File(dir, FILE).delete() }
    }

    private fun parse(block: String): Record? = block.lines().let { lines ->
        val fields = lines.firstOrNull()?.removePrefix(HEADER.toString())?.split(FIELD_SEPARATOR)
            ?.takeIf { it.size == HEADER_FIELDS }
        val at = fields?.firstOrNull()?.toLongOrNull()?.coerceIn(0L, MAX_TIME_MS)
        if (fields == null || at == null) null
        else redacted(Record(at, fields[1], fields[2], lines.drop(1).asSequence()
            .filter { it.startsWith(FRAME_PREFIX) }
            .map { it.removePrefix(FRAME_PREFIX) }.take(MAX_FRAMES).toList()))
    }

    private fun retained(records: List<Record>): List<Record> {
        var kept = records.takeLast(MAX_RECORDS)
        while (kept.size > 1 && serializedSize(kept) > MAX_BYTES) kept = kept.drop(1)
        return kept
    }

    private fun serializedSize(records: List<Record>): Int =
        records.joinToString(SEPARATOR, transform = ::encode).toByteArray(Charsets.UTF_8).size

    private fun bounded(entry: Record): Record {
        var frames = entry.frames
        while (frames.isNotEmpty() &&
            encode(entry.copy(frames = frames)).toByteArray(Charsets.UTF_8).size > MAX_ENTRY_BYTES) {
            frames = frames.dropLast(1)
        }
        return entry.copy(frames = frames)
    }

    private fun encode(record: Record): String = buildString {
        append(HEADER).append(record.atMs).append(FIELD_SEPARATOR)
        append(record.thread).append(FIELD_SEPARATOR).append(record.type)
        record.frames.forEach { append('\n').append(FRAME_PREFIX).append(it) }
    }

    private fun write(dir: File, records: List<Record>) {
        dir.mkdirs()
        val bytes = records.joinToString(SEPARATOR, transform = ::encode).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val temporary = File(dir, "$FILE.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            if (!temporary.renameTo(File(dir, FILE))) throw IOException("Cannot replace crash journal")
        } finally {
            temporary.delete()
        }
    }

}
