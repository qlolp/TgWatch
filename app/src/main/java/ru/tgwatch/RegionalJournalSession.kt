package ru.tgwatch

import java.io.IOException

enum class RegionalJournalError { READ, WRITE, BUSY }
data class RegionalJournalSnapshot(val rows: List<RegionalJournalEntry> = emptyList(),
    val loading: Boolean = true, val error: RegionalJournalError? = null)

/** Serial durable owner. Snapshot reads never acquire the I/O lock; clear invalidates queued commands. */
class RegionalJournalSession(private val storage: RegionalJournalStorage) {
    @Volatile var generation: Long = 0
        private set
    @Volatile var snapshot = RegionalJournalSnapshot()
        private set
    private var pending: List<RegionalJournalEntry>? = null

    @Synchronized fun load(now: Long) {
        if (!snapshot.loading) return
        snapshot = try {
            RegionalJournalSnapshot(RegionalJournal.retained(storage.read(), null, now), false)
        } catch (_: IOException) { RegionalJournalSnapshot(loading = false, error = RegionalJournalError.READ)
        } catch (_: IllegalArgumentException) { RegionalJournalSnapshot(loading = false, error = RegionalJournalError.READ) }
    }

    @Synchronized fun append(ticket: Long, entry: RegionalJournalEntry, now: Long): Boolean {
        if (ticket != generation) return false
        load(now)
        if (snapshot.error == RegionalJournalError.READ) return false
        val candidate = RegionalJournal.retained(pending ?: snapshot.rows, entry, now)
        if (candidate == snapshot.rows && pending == null) return true
        pending = candidate
        return try {
            storage.write(candidate)
            snapshot = RegionalJournalSnapshot(candidate, false)
            pending = null
            true
        } catch (_: IOException) {
            snapshot = snapshot.copy(error = RegionalJournalError.WRITE)
            false
        }
    }

    @Synchronized fun clear() {
        generation = Math.addExact(generation, 1L)
        storage.write(emptyList())
        pending = null
        snapshot = RegionalJournalSnapshot(loading = false)
    }

    fun busy() { snapshot = snapshot.copy(error = RegionalJournalError.BUSY) }
}
