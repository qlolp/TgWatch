package ru.tgwatch

enum class RegionalJournalError { READ, WRITE, BUSY }
data class RegionalJournalSnapshot(val rows: List<RegionalJournalEntry> = emptyList(),
    val loading: Boolean = true, val error: RegionalJournalError? = null)

/** Serial durable owner. Snapshot reads never acquire the I/O lock; clear invalidates queued commands. */
// The file boundary includes vendor fsync/permission failures and strict decoding.
@Suppress("TooGenericExceptionCaught")
class RegionalJournalSession(private val storage: RegionalJournalStorage) {
    @Volatile var generation: Long = 0
        private set
    @Volatile var snapshot = RegionalJournalSnapshot()
        private set
    private var pending: List<RegionalJournalEntry>? = null

    @Synchronized fun load(now: Long) {
        if (!snapshot.loading && snapshot.error != RegionalJournalError.READ) return
        val rows = try {
            storage.read()
        } catch (_: Exception) {
            snapshot = RegionalJournalSnapshot(loading = false, error = RegionalJournalError.READ)
            null
        }
        if (rows != null) {
            snapshot = RegionalJournalSnapshot(rows, false)
            val retained = RegionalJournal.retained(rows, null, now)
            if (retained != rows) persist(retained)
        }
    }

    @Synchronized fun prune(ticket: Long, now: Long): Boolean {
        load(now)
        return if (ticket != generation || snapshot.error == RegionalJournalError.READ) false else {
            val retained = RegionalJournal.retained(pending ?: snapshot.rows, null, now)
            if (retained == snapshot.rows && pending == null) true else persist(retained)
        }
    }

    @Synchronized fun append(ticket: Long, entry: RegionalJournalEntry, now: Long): Boolean {
        return if (ticket != generation) false else appendCurrent(entry, now)
    }

    private fun appendCurrent(entry: RegionalJournalEntry, now: Long): Boolean {
        load(now)
        if (snapshot.error == RegionalJournalError.READ) return false
        val candidate = RegionalJournal.retained(pending ?: snapshot.rows, entry, now)
        return if (candidate == snapshot.rows && pending == null) true else persist(candidate)
    }

    private fun persist(candidate: List<RegionalJournalEntry>): Boolean {
        pending = candidate
        return try {
            storage.write(candidate)
            snapshot = RegionalJournalSnapshot(candidate, false)
            pending = null
            true
        } catch (_: Exception) {
            snapshot = snapshot.copy(error = RegionalJournalError.WRITE)
            false
        }
    }

    @Synchronized fun clear() {
        generation = Math.addExact(generation, 1L)
        // A failed clear must not let a later append merge deleted rows back into the file.
        pending = emptyList()
        try {
            storage.write(emptyList())
            pending = null
            snapshot = RegionalJournalSnapshot(loading = false)
        } catch (failure: Exception) {
            snapshot = snapshot.copy(loading = false, error = RegionalJournalError.WRITE)
            throw failure
        }
    }

    @Synchronized fun clearIfCurrent(ticket: Long): Boolean {
        if (ticket != generation) return false
        clear()
        return true
    }
}
