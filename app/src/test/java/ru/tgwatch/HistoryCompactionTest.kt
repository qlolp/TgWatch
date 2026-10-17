package ru.tgwatch

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HistoryCompactionTest {
    private lateinit var directory: File
    private lateinit var ctx: Context
    private val saved = mutableMapOf<String, Any?>()
    private fun field(name: String) = History::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before fun isolate() {
        directory = Files.createTempDirectory("history-compaction").toFile()
        ctx = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun createDeviceProtectedStorageContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        for (name in listOf("samples", "loaded", "migrated", "dirty", "lastCompacted", "version",
            "appliedPreparation", "physicalRows", "failure")) {
            saved[name] = field(name).get(null)
        }
        field("samples").set(null, mutableListOf<Observation>())
        field("loaded").setBoolean(null, true)
        field("migrated").setBoolean(null, true)
        field("dirty").setBoolean(null, false)
        field("lastCompacted").setLong(null, System.currentTimeMillis())
        field("physicalRows").setInt(null, 0)
        field("failure").set(null, null)
        (field("compaction").get(null) as HistoryCompaction).savedReplacement(System.currentTimeMillis())
        resetPreparation()
    }

    private fun resetPreparation() {
        (field("preparation").get(null) as HistoryPreparationCache).clear()
        field("appliedPreparation").set(null, null)
        (field("statsCache").get(null) as MutableMap<*, *>).clear()
    }

    @After fun restore() {
        saved.forEach { (name, value) -> field(name).set(null, value) }
        resetPreparation()
        directory.deleteRecursively()
    }

    @Test fun steadyRetentionAppendsInsteadOfRewriting() {
        val now = System.currentTimeMillis()
        val start = now - 7 * 86_400_000L
        val rows = (0 until 60_480).map { index ->
            val at = start + index * 10_000L
            Observation(at, at + 10_000L, "OK", 100)
        }
        val file = File(directory, "history-v2.csv")
        HistoryStorage.write(file, rows)
        field("samples").set(null, rows.toMutableList())
        field("physicalRows").setInt(null, rows.size)
        fun identity() = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
        var previous = identity()
        var replacements = 0
        repeat(8) { index ->
            History.record(ctx, now + (index + 1) * 10_000L, History.Kind.OK, 100, 10)
            val current = identity()
            if (current != previous) replacements++
            previous = current
        }
        assertEquals("Normal retention must append, not replace the whole week on every check", 0, replacements)
        assertTrue(HistoryStorage.read(file).size <= 66_024)
        assertTrue(History.snapshot(ctx).size <= 65_000)
    }

    @Test fun physicalJournalIsBounded() {
        val now = System.currentTimeMillis()
        val row = Observation(now - 1, now, "OK", 1)
        val file = File(directory, "history-v2.csv")
        file.writeText(("${row.at},${row.until},OK,1,0\n").repeat(66_024))
        field("samples").set(null, mutableListOf(row))
        field("physicalRows").setInt(null, 66_024)
        History.record(ctx, now, History.Kind.OK, 1)
        assertTrue(HistoryFileReader.readCounted(file).physicalRows <= 65_000)
        assertTrue(History.readStats(ctx, 1, now) is HistoryRead.Ready)
    }

    @Test fun failedCompactionStopsJournalGrowthAndRetryRepairs() {
        val now = System.currentTimeMillis()
        val row = Observation(now - 1, now, "OK", 1)
        val file = File(directory, "history-v2.csv")
        HistoryStorage.write(file, listOf(row))
        field("samples").set(null, mutableListOf(row))
        field("physicalRows").setInt(null, 66_024)
        val blocker = File(directory, "history-v2.csv.tmp").apply { mkdir() }
        val before = file.readBytes()
        repeat(3) { History.record(ctx, now + it, History.Kind.FAIL, -1) }
        assertArrayEquals(before, file.readBytes())
        val failed = History.readStats(ctx, 1, now + 3)
        assertTrue(failed is HistoryRead.Unavailable)
        assertEquals(HistoryFailure.WRITE, (failed as HistoryRead.Unavailable).failure)
        assertTrue(blocker.delete())
        History.record(ctx, now + 4, History.Kind.OK, 1)
        assertTrue(History.readStats(ctx, 1, now + 4) is HistoryRead.Ready)
        assertEquals(5, HistoryStorage.read(file).size)
    }

    @Test fun unreadableHistoryIsNotEmptyStats() {
        File(directory, "history-v2.csv").mkdir()
        field("loaded").setBoolean(null, false)
        val result = runCatching { History.stats(ctx) }
        assertTrue("Read failure must not become an apparently valid empty history", result.isFailure)
    }

    @Test fun unreadableHistoryIsNotNoOutages() {
        File(directory, "history-v2.csv").mkdir()
        field("loaded").setBoolean(null, false)
        val result = runCatching { History.incidents(ctx) }
        assertTrue("Read failure must not become 'no outages'", result.isFailure)
    }

    @Test fun retentionAfterForwardClockJumpCannotReviveDeletedChecks() {
        val now = System.currentTimeMillis()
        val row = Observation(now - 1, now, "OK", 1)
        val file = File(directory, "history-v2.csv")
        HistoryStorage.write(file, listOf(row))
        field("samples").set(null, mutableListOf(row))
        field("physicalRows").setInt(null, 1)
        History.stats(ctx, 1, now + 7 * 86_400_000L + 2_000)
        History.record(ctx, now + 1_000, History.Kind.OK, 1)
        field("loaded").setBoolean(null, false)
        assertEquals(1, Timeline.currentChecks(History.snapshot(ctx)).size)
    }

    @Test fun fullHistoryClockRollbackRemainsDurable() {
        val now = System.currentTimeMillis()
        val rows = (0 until 65_000).map { index ->
            val at = now - 65_000 + index
            Observation(at, at + 1, "OK", 1)
        }
        val file = File(directory, "history-v2.csv")
        HistoryStorage.write(file, rows)
        field("samples").set(null, rows.toMutableList())
        field("physicalRows").setInt(null, rows.size)
        val result = History.readStats(ctx, 1, now - 1_000_000)
        assertTrue("A rollback marker must fit and be durably written at the row cap", result is HistoryRead.Ready)
        assertTrue(Timeline.current(HistoryStorage.read(file)).single().isEpochMetadata)
    }
}
