package ru.tgwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android adapters retain valid extreme values from portable history. */
@RunWith(AndroidJUnit4::class)
class ClockBoundaryIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private var oldSettings: Map<String, *>? = null
    private var oldHistory: List<Observation>? = null
    private var oldLog: List<String>? = null

    @Before fun saveFixture() {
        assertFalse(BackupManager.hasPending(ctx))
        oldSettings = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        oldHistory = History.snapshot(ctx)
        oldLog = EventLog.all(ctx).toList()
    }

    @After fun restoreFixture() {
        if (oldSettings == null) return
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (BackupManager.hasPending(ctx)) BackupManager.recoverPending(ctx)
        oldHistory?.let { History.replaceSnapshot(ctx, it) }
        val edit = Prefs.sp(ctx).edit().clear()
        oldSettings!!.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            null -> edit.remove(key)
            else -> error("Unsupported fixture preference")
        } }
        assertTrue(edit.commit())
        oldLog?.let { EventLog.replace(ctx, it) }
        MonitorService.clearRestoredState()
    }

    @Test fun restoredFutureMaximumEpochRollsOverOnceAndAcceptsFreshChecksAndBackup() {
        val now = System.currentTimeMillis()
        val rows = listOf(Observation(now - 2000, now - 1000, "OK", 9, 7),
            Observation(now - 500, now + 100, "PARTIAL", clockEpoch = Long.MAX_VALUE - 1),
            Observation(now + 60_000, now + 120_000, "OK", 12, Long.MAX_VALUE))
        BackupManager.restore(ctx, BackupSnapshot(now, mapOf("interval_sec" to 30), rows, listOf("boundary fixture")))
        assertEquals(0, History.stats(ctx, 1, now).okChecks)
        val rolled = History.snapshot(ctx)
        assertEquals(listOf(0L, 1L, 2L, 3L), rolled.map { it.clockEpoch })
        assertEquals(Observation(now, now + 1, "UNKNOWN", clockEpoch = 3), rolled.last())
        val version = History.version
        History.stats(ctx, 1, now)
        assertEquals("Reading stats must not repeatedly roll over", version, History.version)
        val file = File(ctx.createDeviceProtectedStorageContext().filesDir, "history-v2.csv")
        assertEquals("Epoch remapping must be durably flushed", rolled, HistoryStorage.read(file))

        History.record(ctx, now + 1, History.Kind.OK, Long.MAX_VALUE)
        val fresh = History.snapshot(ctx)
        assertEquals(3L, fresh.last().clockEpoch)
        assertEquals(1, History.stats(ctx, 1, now + 2).okChecks)
        val encoded = BackupCodec.encode(BackupManager.snapshot(ctx))
        assertEquals(fresh, BackupCodec.decode(encoded).observations)
        assertTrue(fresh.all { it.clockEpoch >= 0 })
    }

    @Test fun extremeLatenciesSurviveMinuteCopyAndChartBucketAggregation() {
        val now = System.currentTimeMillis()
        val base = now / 60_000 * 60_000 - 3 * 60_000
        val rows = listOf(Observation(base + 1, base + 501, "OK", Long.MAX_VALUE),
            Observation(base + 1001, base + 1501, "OK", Long.MAX_VALUE),
            Observation(base + 60_001, base + 60_501, "OK", 0))
        History.replaceSnapshot(ctx, rows)
        val minutes = History.lastMinutes(ctx, 60, now)
        val largest = minutes.first()
        assertEquals(2, largest.ok)
        assertEquals(Long.MAX_VALUE, largest.latencySum)
        assertEquals(Long.MAX_VALUE, largest.avgLatency)
        val copied = largest.copy()
        copied.addLatency(0)
        assertEquals(6148914691236517204L, copied.avgLatency)
        assertEquals("Copy must preserve a separate exact accumulator", Long.MAX_VALUE, largest.avgLatency)
        assertEquals(6148914691236517204L, History.Minute.merge(0L, minutes).avgLatency)
        instrumentation.runOnMainSync {
            val chart = ChartView(ctx)
            chart.setData(minutes, now, 120)
            assertTrue(chart.contentDescription.toString().contains("Среднее время ответа: 6148914691236517204 мс"))
        }
    }
}
