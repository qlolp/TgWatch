package ru.tgwatch

import java.io.File
import java.io.IOException
import java.util.UUID

/** Worker-confined durable generations. A manifest is published only after every constituent is durable. */
class TargetStorage private constructor(private val directory: File, private val io: HistoryFileOperations,
    private val elapsedMs: () -> Long) {
    @Volatile private var active: String? = TargetManifest.read(directory)
    private val writer = TargetJournalWriter(::read, ::replaceRows, ::append, { journalFile(it).length() }, elapsedMs)
    val revisions: Map<TargetId, Long> get() = writer.revisions
    val isInitialized: Boolean get() = active != null
    val generationId: String? get() = active
    val legacyMigrationComplete: Boolean get() = File(generation, LEGACY_COMPLETE).isFile

    @Volatile private var registered: Set<TargetId>? = null

    /** A goal added after this generation was written has no journal yet; that is empty, not damaged. */
    @Synchronized fun read(id: TargetId): List<TargetObservation> {
        val file = journalFile(id)
        val known = registered ?: registry().targets.map { it.id }.toSet().also { registered = it }
        if (!file.isFile && id !in known) return emptyList()
        return TargetJournal.retained(TargetJournal.read(file, id), Long.MIN_VALUE, TargetBackupCodec.rowLimit(id))
    }

    @Synchronized fun snapshot(): TargetBackupSnapshot {
        val registry = registry()
        val known = registry.targets.map { it.id }.toSet()
        return registry.copy(targets = registry.targets + TargetId.entries.filter { it !in known }
            .map { TargetDefinition.default(it).copy(enabled = false) },
            observations = TargetId.entries.associateWith { read(it) })
    }

    /** Path lookup is deliberately lock-free; callers hold the History monitor while switching generations. */
    fun journalFile(id: TargetId): File = File(generation, "${id.wire}.csv")

    @Synchronized fun recordedRows(id: TargetId, revision: Long? = null,
        now: Long = System.currentTimeMillis()): HistoryRead<List<TargetObservation>> {
        try { if (revision != null) writer.prepare(id, revision, now) }
        catch (_: IOException) { /* The cached reader exposes the typed debt and retained rows. */ }
        return writer.read(id)
    }

    @Synchronized fun replaceGeneration(data: TargetBackupSnapshot, supersedeLegacy: Boolean = true,
        materialize: () -> Unit = {}) {
        TargetBackupCodec.validate(data)
        val name = UUID.randomUUID().toString().replace("-", "")
        val staged = File(directory, name)
        val previous = active
        check(staged.mkdir()) { "Cannot create target generation" }
        var published = false
        val installed = runCatching {
            for (id in TargetId.entries) TargetJournal.write(File(staged, "${id.wire}.csv"),
                data.observations[id].orEmpty(), io)
            val bytes = TargetBackupCodec.encode(data.copy(observations = data.observations.mapValues { emptyList() }))
            try { TargetJournal.writeBytes(File(staged, REGISTRY), bytes, io) } finally { bytes.fill(0) }
            if (supersedeLegacy) TargetJournal.writeBytes(File(staged, LEGACY_COMPLETE), byteArrayOf(), io)
            io.syncDirectory(staged)
            io.syncDirectory(directory)
            io.syncDirectory(directory.absoluteFile.parentFile!!)
            val temporary = File(directory, "$MANIFEST.tmp")
            TargetJournal.writeBytes(temporary, TargetManifest.bytes(name), io)
            io.replace(temporary, File(directory, MANIFEST))
            published = true
            io.syncDirectory(directory)
            active = name
            registered = null
            materialize()
            writer.clear()
        }
        installed.exceptionOrNull()?.let { failure ->
            if (failure is Error) throw failure
            if (published) runCatching {
                TargetManifest.restore(directory, previous)
                active = previous
                registered = null
            }.exceptionOrNull()?.let(failure::addSuppressed)
            val retired = !published || runCatching { TargetManifest.read(directory) != name }.getOrDefault(false)
            if (retired) staged.deleteRecursively()
            throw failure
        }
    }

    @Synchronized fun replaceRows(id: TargetId, rows: List<TargetObservation>) {
        require(rows.size <= TargetBackupCodec.rowLimit(id))
        rows.forEach(TargetBackupCodec::validateRow)
        val file = journalFile(id)
        val temporary = File(file.parentFile, file.name + ".tmp")
        TargetJournal.write(temporary, rows, io)
        io.replace(temporary, file)
        io.syncDirectory(file.parentFile!!)
        writer.replaced(id, rows)
    }

    /** The Telegram facade already owns bounded memory, epochs, retention and repair debt. */
    @Synchronized fun append(id: TargetId, value: TargetObservation, sync: Boolean) {
        TargetBackupCodec.validateRow(value)
        TargetJournal.append(journalFile(id), value, sync, io)
    }

    @Synchronized fun record(id: TargetId, row: Observation, endpointRevision: Long) {
        writer.record(id, row, endpointRevision)
    }

    /** Invoke only after the enclosing history/settings transaction is confirmed durable. */
    @Synchronized fun cleanupRetired() {
        val selected = active ?: return
        directory.listFiles()?.filter { it.isDirectory && it.name != selected && GENERATION.matches(it.name) }
            ?.forEach { check(it.deleteRecursively()) { "Cannot remove retired history" } }
        io.syncDirectory(directory)
    }

    private fun registry(): TargetBackupSnapshot = File(generation, REGISTRY).inputStream().use {
        TargetBackupCodec.decode(BoundedInput.read(it, TargetBackupCodec.MAX_PLAINTEXT_BYTES))
    }
    private val generation: File get() = File(directory, active ?: throw IOException("Targets are not initialized"))


    companion object {
        private const val MANIFEST = "active-generation"
        private const val REGISTRY = "registry.json"
        private const val LEGACY_COMPLETE = "legacy-superseded"
        private val GENERATION = Regex("[a-f0-9]{32}")
        fun open(directory: File, io: HistoryFileOperations = SystemHistoryFileOperations,
            elapsedMs: () -> Long = { java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
            validateRegistry: Boolean = true): TargetStorage {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create target storage")
            return TargetStorage(directory, io, elapsedMs).also { store ->
                // Each journal reports its own read failure; a damaged goal cannot disable the others.
                if (validateRegistry && store.isInitialized) store.registry()
            }
        }
    }
}
