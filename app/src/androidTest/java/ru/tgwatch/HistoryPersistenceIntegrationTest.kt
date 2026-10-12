package ru.tgwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the actual singleton, device-protected file adapter and Android directory fsync. */
@RunWith(AndroidJUnit4::class)
class HistoryPersistenceIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val file get() = File(ctx.createDeviceProtectedStorageContext().filesDir, "history-v2.csv")
    private val held get() = File(file.parentFile, "history-persistence-fixture-held")
    private var original: List<Observation>? = null
    private var enabled = false

    @Before fun saveFixture() {
        enabled = Prefs.isEnabled(ctx)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        original = History.snapshot(ctx)
        History.replaceSnapshot(ctx, emptyList())
        assertFalse(held.exists())
    }

    @After fun restoreFixture() {
        if (file.isDirectory) assertTrue(file.delete())
        if (held.exists()) { file.delete(); assertTrue(held.renameTo(file)) }
        File(file.parentFile, file.name + ".tmp").let { if (it.isDirectory) it.delete() }
        original?.let { History.replaceSnapshot(ctx, it) }
        Prefs.setEnabled(ctx, enabled)
        MonitorService.clearRestoredState()
    }

    private fun obstruct() {
        assertTrue(file.renameTo(held))
        assertTrue(file.mkdir())
    }

    private fun recover() {
        assertTrue(file.delete())
        assertTrue(held.renameTo(file))
    }

    private fun reload(): List<Observation> {
        History::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
        return History.snapshot(ctx)
    }

    @Test fun failedAppendIsRepairedByTheNextRecordBeforeDiskReload() {
        val now = System.currentTimeMillis() - 10_000
        History.record(ctx, now, History.Kind.OK, 10)
        obstruct()
        History.record(ctx, now + 1000, History.Kind.FAIL, -1)
        recover()
        History.record(ctx, now + 2000, History.Kind.OK, 12)
        assertEquals(listOf(now, now + 1000, now + 2000), reload().map { it.at })
    }

    @Test fun repeatedFailedRepairsKeepEveryUnsavedRowUntilRecovery() {
        val now = System.currentTimeMillis() - 10_000
        History.record(ctx, now, History.Kind.OK, 10)
        obstruct()
        History.record(ctx, now + 1000, History.Kind.FAIL, -1)
        History.record(ctx, now + 2000, History.Kind.UNKNOWN, -1)
        History.flush(ctx)
        recover()
        History.record(ctx, now + 3000, History.Kind.OK, 12)
        assertEquals((0..3).map { now + it * 1000 }, reload().map { it.at })
    }

    @Test fun failedFullWriteIsRepairedByTheNextRecord() {
        val now = System.currentTimeMillis() - 10_000
        History.record(ctx, now, History.Kind.OK, 10)
        obstruct()
        History::class.java.getDeclaredField("lastCompacted").apply { isAccessible = true }.setLong(null, 0L)
        History.record(ctx, now + 1000, History.Kind.FAIL, -1)
        recover()
        History.record(ctx, now + 2000, History.Kind.OK, 12)
        assertEquals(listOf(now, now + 1000, now + 2000), reload().map { it.at })
    }

    @Test fun successfulFlushRepairsDebtAndResumesAppends() {
        val now = System.currentTimeMillis() - 10_000
        History.record(ctx, now, History.Kind.OK, 10)
        obstruct()
        History.record(ctx, now + 1000, History.Kind.FAIL, -1)
        recover()
        History.flush(ctx)
        assertEquals(listOf(now, now + 1000), HistoryStorage.read(file).map { it.at })
        // A clean record must append; a redundant full repair would fail here.
        assertTrue(File(file.parentFile, file.name + ".tmp").mkdir())
        History.record(ctx, now + 2000, History.Kind.OK, 12)
        assertEquals(listOf(now, now + 1000, now + 2000), reload().map { it.at })
    }

    @Test fun failedSessionEndRemainsDirtyUntilTheNextRecord() {
        val now = System.currentTimeMillis() - 10_000
        History.record(ctx, now, History.Kind.OK, 10)
        obstruct()
        History.endSession(ctx, now + 1000)
        recover()
        History.record(ctx, now + 2000, History.Kind.OK, 12)
        assertEquals(now + 1000, reload().first().until)
    }

    @Test fun pruningChangesAreRepairedBeforeTheNextAppend() {
        val now = System.currentTimeMillis()
        History.replaceSnapshot(ctx, listOf(Observation(now, now + 500, "OK", 10)))
        History.stats(ctx, 1, now + 7 * 86_400_000L + 2000)
        History.record(ctx, now + 1000, History.Kind.OK, 12)
        assertEquals(History.snapshot(ctx), HistoryStorage.read(file))
        assertEquals(1, Timeline.stats(reload(), now - 1000, now + 2000).checks)
    }

    @Test fun nextRecordKeepsBothMemoryAndDiskWithinTheRowLimit() {
        val now = System.currentTimeMillis()
        val rows = (0 until 65_000).map { index ->
            val at = now - 65_000 + index
            Observation(at, at + 1, "OK", 10)
        }
        History.replaceSnapshot(ctx, rows)
        History.record(ctx, now + 1000, History.Kind.OK, 12)
        assertEquals(65_000, HistoryStorage.read(file).size)
        assertEquals(History.snapshot(ctx), HistoryStorage.read(file))
        assertEquals(65_000, reload().size)
    }

    @Test fun restoredSnapshotSupersedesUnsavedRowsAndResumesCleanAppends() {
        val now = System.currentTimeMillis() - 10_000
        History.record(ctx, now, History.Kind.OK, 10)
        obstruct()
        History.record(ctx, now + 1000, History.Kind.FAIL, -1)
        recover()
        val replacement = listOf(Observation(now + 2000, now + 3000, "PARTIAL"))
        History.replaceSnapshot(ctx, replacement)
        assertTrue(File(file.parentFile, file.name + ".tmp").mkdir())
        val freshAt = System.currentTimeMillis() + 1000
        History.record(ctx, freshAt, History.Kind.OK, 12)
        val reloaded = reload()
        assertEquals(listOf(replacement.single().at, freshAt), reloaded.map { it.at })
        assertFalse(reloaded.any { it.kind == "TG_DOWN" })
    }

    @Test fun rollbackWithoutAProbeNeverAppearsInStatsOrChartAndRoundTrips() {
        val now = System.currentTimeMillis()
        History.replaceSnapshot(ctx, listOf(Observation(now + 5000, now + 6000, "OK")))
        History.stats(ctx, 1, now)
        // Cross the cache's minute boundary so the later check count is recomputed.
        assertEquals(0, History.stats(ctx, 1, now + 61_000).checks)
        assertTrue(History.lastMinutes(ctx, 10, now + 61_000).isEmpty())
        val marker = History.snapshot(ctx).last()
        assertEquals(marker.at, marker.until)
        assertEquals(1L, marker.clockEpoch)
        assertEquals(1L, Timeline.current(reload()).single().clockEpoch)
        val portable = History.snapshot(ctx, now)
        assertEquals(1L, Timeline.current(portable).single().clockEpoch)
        assertEquals(0, Timeline.stats(portable, now - 1000, now + 2000).checks)
        assertEquals(portable, BackupCodec.decode(BackupCodec.encode(BackupSnapshot(now, emptyMap(), portable, emptyList()))).observations)
    }

    @Test fun sameTimeRealUnknownCheckCountsOnceAndAppearsOnceInChart() {
        val now = System.currentTimeMillis() - 2000
        History.replaceSnapshot(ctx, listOf(Observation(now + 5000, now + 6000, "OK")))
        History.stats(ctx, 1, now)
        History.record(ctx, now, History.Kind.UNKNOWN, -1)
        assertEquals(1, History.stats(ctx, 1, now + 1000).unknownChecks)
        assertEquals(1, History.lastMinutes(ctx, 10, now + 1000).sumOf { it.unknown })
        assertEquals(1, Timeline.stats(reload(), now - 1000, now + 1000).checks)
    }

    @Test fun genuineOneMillisecondUnknownSurvivesAdaptersAndChartAsARealCheck() {
        val now = System.currentTimeMillis()
        val row = Observation(now - 1000, now - 999, "UNKNOWN", clockEpoch = 1)
        History.replaceSnapshot(ctx, listOf(row))
        assertEquals(1, History.stats(ctx, 1, now).unknownChecks)
        assertEquals(1, History.lastMinutes(ctx, 10, now).sumOf { it.unknown })
        assertEquals(listOf(row), reload())
        assertEquals(listOf(row), BackupCodec.decode(BackupCodec.encode(
            BackupSnapshot(now, emptyMap(), History.snapshot(ctx, now), emptyList()))).observations)
    }
}
