package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetHistoryIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var saved: TargetBackupSnapshot
    private lateinit var preferences: Map<String, *>
    private var session = 0L
    @Before fun save() {
        HistoryTestStorage.awaitReady()
        preferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        saved = BackupManager.snapshotTargets(ctx)
    }
    @After fun restore() {
        if (session != 0L) HistorySessions.queue(ctx).close(session, System.currentTimeMillis()).get()
        if (::saved.isInitialized) BackupManager.restoreTargets(ctx, saved)
        if (::preferences.isInitialized) restore(Prefs.sp(ctx), preferences)
    }

    @Test fun secondaryOnlySessionPersistsItsStateAndNeverClipsUntouchedTelegram() {
        val now = System.currentTimeMillis()
        val telegram = listOf(TargetObservation(Observation(now - 1_000, now + 45_000, "OK"),
            TargetPreferences.endpointRevision(ctx, TargetId.TELEGRAM)))
        HistorySessions.queue(ctx).exclusive {
            TargetPersistence.storage().replaceRows(TargetId.TELEGRAM, telegram)
            History.invalidateTargetCache(TargetPersistence.storage().legacyMigrationComplete)
            TargetPersistence.storage().replaceRows(TargetId.MATRIX, emptyList())
        }.get()
        session = HistorySessions.queue(ctx).open()
        val revision = TargetPreferences.endpointRevision(ctx, TargetId.MATRIX)
        val snapshot = TargetSnapshot(TargetId.MATRIX, TargetStatus.DOWN, now, latencyMs = 150,
            endpointRevision = revision)
        assertTrue(TargetHistories.publish(ctx, session, snapshot))
        HistorySessions.queue(ctx).close(session, now + 1_000).get()
        assertEquals(telegram, TargetPersistence.storage().read(TargetId.TELEGRAM))
        val matrix = TargetHistories.rows(ctx, TargetId.MATRIX, now + 1_000)
        assertEquals(1, matrix.size)
        assertEquals("DOWN", matrix.single().kind)
        assertEquals(now + 1_000, matrix.single().until)
        assertEquals(TargetStatus.DOWN, TargetStateStore.load(ctx, TargetId.MATRIX).status)
        assertEquals(1, TargetHistories.stats(ctx, TargetId.MATRIX, 1, now + 1_000).valueOrThrow().downChecks)
    }

    @Test fun unmeasuredGoalDoesNotCreateHistoryAndLastSuccessIsIndependent() {
        val now = System.currentTimeMillis()
        val customSuccess = LastSuccessStore.lastSuccessAt(ctx, TargetId.CUSTOM)
        session = HistorySessions.queue(ctx).open()
        val before = TargetPersistence.storage().read(TargetId.CUSTOM)
        assertFalse(TargetHistories.publish(ctx, session, TargetSnapshot(TargetId.CUSTOM, checkedAtMs = now)))
        val revision = TargetPreferences.endpointRevision(ctx, TargetId.MATRIX)
        assertTrue(TargetHistories.publish(ctx, session,
            TargetSnapshot(TargetId.MATRIX, TargetStatus.OK, now, endpointRevision = revision)))
        HistorySessions.queue(ctx).close(session, now + 100).get()
        assertEquals(before, TargetPersistence.storage().read(TargetId.CUSTOM))
        assertEquals(now, LastSuccessStore.lastSuccessAt(ctx, TargetId.MATRIX))
        assertEquals(customSuccess, LastSuccessStore.lastSuccessAt(ctx, TargetId.CUSTOM))
    }

    @Test fun failedSecondaryAppendRemainsTypedButStatePersistsAndStopRepairsHistory() {
        val now = System.currentTimeMillis()
        val id = TargetId.MATRIX
        val revision = TargetPreferences.endpointRevision(ctx, id)
        HistorySessions.queue(ctx).exclusive { TargetPersistence.storage().replaceRows(id, emptyList()) }.get()
        session = HistorySessions.queue(ctx).open()
        assertTrue(TargetHistories.publish(ctx, session,
            TargetSnapshot(id, TargetStatus.OK, now, endpointRevision = revision)))
        HistorySessions.queue(ctx).exclusive {}.get()
        val file = TargetPersistence.storage().journalFile(id)
        assertTrue(file.setWritable(false, false))
        try {
            assertTrue(TargetHistories.publish(ctx, session,
                TargetSnapshot(id, TargetStatus.DOWN, now + 1_000, endpointRevision = revision)))
            HistorySessions.queue(ctx).exclusive {}.get()
            val failed = TargetHistories.stats(ctx, id, 1, now + 2_000) as HistoryRead.Unavailable
            assertEquals(HistoryFailure.WRITE, failed.failure)
            assertEquals(1, failed.lastKnown!!.downChecks)
            assertEquals(TargetStatus.DOWN, TargetStateStore.load(ctx, id).status)
            HistorySessions.queue(ctx).close(session, now + 2_000).get()
            assertTrue(TargetHistories.stats(ctx, id, 1, now + 2_000) is HistoryRead.Ready)
            assertNull(TargetHistories.failures[id])
            assertEquals(2, TargetPersistence.storage().read(id).count { !it.row.isEpochMetadata })
        } finally { file.setWritable(true, true); HistorySessions.queue(ctx).close(session, now + 2_000).get() }
    }
    private fun restore(store: SharedPreferences, data: Map<String, *>) {
        val edit = store.edit().clear()
        data.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(edit.commit())
    }
}
