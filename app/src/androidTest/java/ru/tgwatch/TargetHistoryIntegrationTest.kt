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
    private lateinit var privatePreferences: Map<String, *>
    private var session = 0L
    @Before fun save() {
        HistoryTestStorage.awaitReady()
        preferences = Prefs.sp(ctx).all.toMap()
        privatePreferences = ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        saved = BackupManager.snapshotTargets(ctx)
    }
    @After fun restore() {
        if (session != 0L) HistorySessions.queue(ctx).close(session, System.currentTimeMillis()).get()
        if (::saved.isInitialized) BackupManager.restoreTargets(ctx, saved)
        if (::privatePreferences.isInitialized) restore(ctx.getSharedPreferences(
            TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE), privatePreferences)
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
            val changed = TargetPreferences.setEndpoint(ctx, id, "https://new.example")
            val afterChange = TargetPersistence.storage().recordedRows(id)
            val kept = when (afterChange) {
                is HistoryRead.Ready -> afterChange.value
                is HistoryRead.Unavailable -> afterChange.lastKnown.orEmpty()
            }
            assertTrue("An endpoint change must retain the unsaved measured row in its original cohort",
                kept.any { it.endpointRevision == revision && it.row.at == now + 1_000 && it.row.kind == "DOWN" })
            if (changed) assertTrue("An accepted endpoint change must durably retain the measured row",
                TargetPersistence.storage().read(id).any { it.endpointRevision == revision && it.row.kind == "DOWN" })
            HistorySessions.queue(ctx).close(session, now + 2_000).get()
            assertTrue(TargetHistories.stats(ctx, id, 1, now + 2_000) is HistoryRead.Ready)
            assertNull(TargetHistories.failures[id])
            assertEquals(2, TargetPersistence.storage().read(id).count { !it.row.isEpochMetadata })
        } finally { file.setWritable(true, true); HistorySessions.queue(ctx).close(session, now + 2_000).get() }
    }
    @Test fun targetCsvImportReplacesOnlyNamedGoalsAndPreservesOtherPrivateReviewState() {
        val now = System.currentTimeMillis()
        val row = Observation(now - 3_000, now - 2_000, "OK", 25)
        val tg = TargetObservation(row, TargetPreferences.endpointRevision(ctx, TargetId.TELEGRAM))
        val wa = TargetObservation(row, TargetPreferences.endpointRevision(ctx, TargetId.WHATSAPP))
        HistorySessions.queue(ctx).exclusive {
            TargetPersistence.storage().replaceRows(TargetId.TELEGRAM, listOf(tg))
            TargetPersistence.storage().replaceRows(TargetId.WHATSAPP, listOf(wa))
            History.invalidateTargetCache(TargetPersistence.storage().legacyMigrationComplete)
        }.get()
        assertTrue(Prefs.sp(ctx).edit().putBoolean("enabled", true)
            .putBoolean(TargetPreferences.key(TargetId.WHATSAPP, "enabled"), true)
            .putInt(TargetPreferences.key(TargetId.WHATSAPP, "interval"), 90)
            .putBoolean(TargetPreferences.key(TargetId.CUSTOM, "endpoint_review"), false).commit())
        val own = listOf(TargetObservation(row.copy(kind = "DOWN"), 9))
        BackupManager.importHistories(ctx, mapOf(TargetId.MATRIX to own))
        assertEquals(listOf(tg), TargetPersistence.storage().read(TargetId.TELEGRAM))
        assertEquals(listOf(wa), TargetPersistence.storage().read(TargetId.WHATSAPP))
        assertEquals(own, TargetPersistence.storage().read(TargetId.MATRIX))
        assertEquals(9L, TargetPreferences.endpointRevision(ctx, TargetId.MATRIX))
        assertEquals(90, TargetPreferences.storedDefinition(ctx, TargetId.WHATSAPP).intervalSec)
        assertFalse(Prefs.sp(ctx).getBoolean(TargetPreferences.key(TargetId.CUSTOM, "endpoint_review"), true))
        assertEquals(privatePreferences, ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE).all)
        assertFalse(Prefs.isEnabled(ctx))
        assertTrue(TargetId.entries.none { TargetPreferences.storedDefinition(ctx, it).enabled })
    }

    @Test fun incidentsAndDiagnosticsReadOnlyTheirOwnGoalAndCurrentEndpoint() {
        val now = System.currentTimeMillis()
        val revision = TargetPreferences.endpointRevision(ctx, TargetId.MATRIX)
        val rows = listOf(Observation(now - 3_000, now - 2_000, "OK"),
            Observation(now - 2_000, now - 1_000, "DOWN"), Observation(now - 1_000, now, "OK"))
        HistorySessions.queue(ctx).exclusive {
            TargetPersistence.storage().replaceRows(TargetId.MATRIX, rows.map { TargetObservation(it, revision) })
        }.get()
        val cards = IncidentDisplay.load(ctx, 1, TargetId.MATRIX).cards
        assertEquals(1, cards.size)
        assertTrue(cards.single().contains("Matrix"))
        assertFalse(cards.single().contains("Telegram"))
        val report = org.json.JSONObject(LocalDiagnostics.snapshot(ctx).toJson()).getJSONArray("targets")
        val goal = (0 until report.length()).map(report::getJSONObject).single { it.getString("target_id") == "matrix" }
        assertEquals(1, goal.getJSONObject("last_24_hours").getInt("outage_count"))
        assertEquals(3, goal.getJSONObject("last_24_hours").getInt("checks"))
        assertFalse(report.toString().contains("https://"))
    }

    @Test fun restoreInvalidatesQueuedSettingsCapturedBeforeReplacement() {
        val oldRevision = TargetPreferences.fence.snapshot(TargetId.TELEGRAM).revision
        val oldEnable = TargetPreferences.storedDefinition(ctx, TargetId.TELEGRAM).copy(enabled = true)
        BackupManager.restoreTargets(ctx, saved)
        assertFalse("A settings click captured before restore must not reactivate restored data",
            TargetSettingWrites.definition(ctx, oldEnable, oldRevision))
        assertFalse(Prefs.isEnabled(ctx))
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
