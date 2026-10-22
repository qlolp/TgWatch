package ru.tgwatch

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetRestoreIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var saved: TargetBackupSnapshot
    private lateinit var preferences: Map<String, *>
    private var endpoint: String? = null

    @Before fun saveAndStop() {
        HistoryTestStorage.awaitReady()
        check(!BackupManager.hasPending())
        preferences = Prefs.sp(ctx).all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        TargetPersistence.initialize(ctx)
        saved = BackupManager.snapshotTargets(ctx)
        endpoint = ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)
            .getString("extra_custom", null)
    }

    @After fun restoreFixture() {
        if (!::saved.isInitialized) return
        if (BackupManager.hasPending()) BackupManager.recoverPending(ctx)
        BackupManager.restoreTargets(ctx, saved)
        val edit = Prefs.sp(ctx).edit().clear()
        preferences.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
        } }
        check(edit.commit())
        check(ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)
            .edit().putString("extra_custom", endpoint).commit())
        EventLog.invalidate()
    }

    private fun data(): TargetBackupSnapshot {
        val now = System.currentTimeMillis()
        return TargetBackupSnapshot(now, mapOf("interval_sec" to 41),
            TargetId.entries.map { TargetDefinition.default(it).copy(enabled = true) },
            TargetId.entries.associateWith { id -> listOf(
                TargetObservation(Observation(now - 2000, now - 1000, "DOWN", 12, 3), 4),
                TargetObservation(Observation(now - 1000, now - 1000, "UNKNOWN", -1, 3), 5)) },
            listOf("restored public log"))
    }

    @Test fun restoresAllHistoriesTogetherButEveryTargetRemainsStopped() {
        val source = data()
        for (service in RegionalService.entries) assertTrue(RegionalPreferences.setEnabled(ctx, service, true))
        Prefs.sp(ctx).edit().putLong("regional.youtube.delivered_at", 1234).commit()
        BackupManager.restoreTargets(ctx, source)
        assertFalse(Prefs.isEnabled(ctx))
        assertTrue(TargetPreferences.definitions(ctx).none { it.enabled })
        assertFalse(RegionalPreferences.anyEnabled(ctx))
        assertTrue(Prefs.sp(ctx).all.keys.none { it.startsWith(RegionalPreferences.PREFIX) })
        assertEquals(source.targets.map { it.copy(enabled = false) }, TargetPreferences.definitions(ctx))
        assertEquals(source.targets.first { it.id == TargetId.TELEGRAM }.intervalSec, Prefs.intervalSec(ctx))
        val restored = TargetPersistence.storage().snapshot()
        assertEquals(source.observations, restored.observations)
        assertEquals(source.log, EventLog.all(ctx))
        for (id in TargetId.entries) assertEquals(5L, TargetPreferences.endpointRevision(ctx, id))
        assertFalse(BackupManager.hasPending())
        assertFalse(BackupManager.recoverPending(ctx))
    }

    @Test fun completeAuthenticationPrecedesAnyChangeToTargetsOrPreferences() {
        val original = TargetPersistence.storage().snapshot()
        val pass = "correct backup password".toCharArray()
        val bytes = PortableBackupCodec.encryptTargets(data(), pass)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        try { PortableBackupCodec.decryptTargets(bytes, pass); fail("Tampered file accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(original, TargetPersistence.storage().snapshot())
        assertFalse(BackupManager.hasPending())
    }

    @Test fun csvImportPreservesOtherTargetHistoriesAndDefinitions() {
        BackupManager.restoreTargets(ctx, data())
        check(Prefs.sp(ctx).edit().putBoolean(TargetPreferences.key(TargetId.CUSTOM, "endpoint_review"), false).commit())
        val original = TargetPersistence.storage().snapshot()
        val now = System.currentTimeMillis()
        val rows = listOf(Observation(now - 1000, now, "TG_DOWN", 17, 6))
        BackupManager.importHistory(ctx, rows)
        assertEquals(rows, History.snapshot(ctx))
        for (id in TargetId.entries.filter { it != TargetId.TELEGRAM })
            assertEquals(original.observations[id], TargetPersistence.storage().read(id))
        assertTrue(TargetPreferences.definitions(ctx).none { it.enabled })
        assertFalse(Prefs.sp(ctx).getBoolean(TargetPreferences.key(TargetId.CUSTOM, "endpoint_review"), true))
    }

    @Test fun legacyCredentialMovesToPrivateStorageAndNeverAppearsInV3Export() {
        val secret = "https://example.org/private-import-path"
        val legacy = BackupSnapshot(System.currentTimeMillis(), mapOf("extra_probe_custom_url" to secret,
            "extra_probe_custom" to true), emptyList(), emptyList())
        val pending = File(ctx.createDeviceProtectedStorageContext().filesDir, "restore-pending-v1.json")
        pending.writeBytes(BackupCodec.encode(legacy))
        assertTrue(BackupManager.recoverPending(ctx))
        assertFalse(pending.exists())
        assertFalse(Prefs.sp(ctx).contains("extra_probe_custom_url"))
        assertEquals(secret, Prefs.extraCustomUrl(ctx))
        assertFalse(TargetPreferences.definition(ctx, TargetId.CUSTOM).enabled)
        val bytes = TargetBackupCodec.encode(BackupManager.snapshotTargets(ctx))
        assertFalse(bytes.toString(Charsets.UTF_8).contains(secret))
    }

    @Test fun materializationFailureRollsBackWholePublicGenerationAndPrivateEndpointForRetry() {
        check(PrivateEndpoints.setLegacy(ctx, "https://example.org/original"))
        val original = TargetPersistence.storage().snapshot()
        val settings = Prefs.sp(ctx).all.toMap()
        val log = EventLog.all(ctx)
        val imported = TargetBackupImport(data(), "https://example.org/replacement")
        TargetRestore.stage(ctx, imported)
        try {
            TargetRestore.apply(ctx, imported, emptySet(), syncPreferences = {
                throw java.io.IOException("injected preferences directory fsync failure")
            })
            fail("Failed materialization was accepted")
        } catch (_: java.io.IOException) { }
        assertEquals(original, TargetPersistence.storage().snapshot())
        assertEquals(settings, Prefs.sp(ctx).all)
        assertEquals(log, EventLog.all(ctx))
        assertEquals("https://example.org/original", Prefs.extraCustomUrl(ctx))
        assertTrue(BackupManager.hasPending())
        assertTrue(BackupManager.recoverPending(ctx))
        assertFalse(BackupManager.hasPending())
        assertEquals("https://example.org/replacement", Prefs.extraCustomUrl(ctx))
    }

    @Test fun restoringSubsetReexportsKnownTargetsWithoutInventingOtherHistories() {
        val source = data().let { it.copy(targets = it.targets.filter { it.id == TargetId.MATRIX },
            observations = it.observations.filterKeys { it == TargetId.MATRIX }) }
        BackupManager.restoreTargets(ctx, source)
        val exported = BackupManager.snapshotTargets(ctx)
        assertEquals(TargetId.entries.toSet(), exported.observations.keys)
        assertTrue(exported.targets.none { it.enabled })
        for (id in TargetId.entries.filter { it != TargetId.MATRIX })
            assertTrue(exported.observations.getValue(id).isEmpty())
        TargetBackupCodec.decode(TargetBackupCodec.encode(exported))
    }

    @Test fun pendingRestoreRejectsConcurrentEndpointSettingsFromUi() {
        check(PrivateEndpoints.setLegacy(ctx, "https://example.org/original"))
        TargetRestore.stage(ctx, TargetBackupImport(data()))
        assertFalse(Prefs.setExtraCustomUrl(ctx, "https://example.org/concurrent"))
        assertEquals("https://example.org/original", Prefs.extraCustomUrl(ctx))
        assertTrue(BackupManager.recoverPending(ctx))
    }

    @Test fun restoredGenerationNeverReimportsLegacyMinuteSourceWhenOldMigrationMarkerWasLost() {
        val legacy = File(ctx.filesDir, "history.csv")
        val marker = File(ctx.createDeviceProtectedStorageContext().filesDir, "history-migrated-v2")
        val previousLegacy = legacy.takeIf { it.isFile }?.readBytes()
        val previousMarker = marker.takeIf { it.isFile }?.readBytes()
        try {
            val minute = (System.currentTimeMillis() - 600_000) / 60_000
            legacy.writeText("$minute,1,0,0,10\n")
            check(marker.delete())
            History::class.java.getDeclaredField("migrated").apply { isAccessible = true }.setBoolean(null, false)
            val source = data()
            BackupManager.restoreTargets(ctx, source)
            History.snapshot(ctx)
            assertEquals(source.observations, TargetPersistence.storage().snapshot().observations)
        } finally {
            if (previousLegacy == null) legacy.delete() else legacy.writeBytes(previousLegacy)
            if (previousMarker == null) marker.delete() else marker.writeBytes(previousMarker)
        }
    }

    @Test fun validBackupRepairsACorruptSecondaryJournalAfterColdReload() {
        val old = TargetPersistence.storage().journalFile(TargetId.MATRIX)
        val bytes = old.readBytes()
        try {
            old.writeText("damaged journal")
            TargetPersistence::class.java.getDeclaredField("current").apply { isAccessible = true }.set(null, null)
            History::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
            val source = data()
            BackupManager.restoreTargets(ctx, source)
            assertEquals(source.observations, TargetPersistence.storage().snapshot().observations)
            assertFalse(BackupManager.hasPending())
        } finally {
            if (TargetPersistence.active?.journalFile(TargetId.MATRIX) == old || TargetPersistence.active == null)
                old.writeBytes(bytes)
        }
    }

    @Test fun validBackupRepairsACorruptRegistryAfterColdReload() {
        val directory = TargetPersistence.storage().journalFile(TargetId.MATRIX).parentFile!!
        val old = File(directory, "registry.json")
        val bytes = old.readBytes()
        try {
            old.writeText("damaged registry")
            TargetPersistence::class.java.getDeclaredField("current").apply { isAccessible = true }.set(null, null)
            History::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
            val source = data()
            BackupManager.restoreTargets(ctx, source)
            assertEquals(source.observations, TargetPersistence.storage().snapshot().observations)
            assertFalse(BackupManager.hasPending())
        } finally {
            if (TargetPersistence.active?.journalFile(TargetId.MATRIX)?.parentFile == directory ||
                TargetPersistence.active == null) old.writeBytes(bytes)
        }
    }

    @Test fun failedRestoreKeepsUnsavedTelegramMemoryAndItsRepairDebt() {
        History.replaceSnapshot(ctx, emptyList())
        val file = TargetPersistence.storage().journalFile(TargetId.TELEGRAM)
        check(file.setReadOnly())
        try {
            val at = System.currentTimeMillis() + 1000
            History.record(ctx, at, History.Kind.FAIL, -1)
            assertEquals(HistoryFailure.WRITE, History.healthFailure)
            val unsaved = History.snapshot(ctx)
            assertEquals(1, unsaved.size)
            val imported = TargetBackupImport(data())
            TargetRestore.stage(ctx, imported)
            try {
                TargetRestore.apply(ctx, imported, emptySet(), syncPreferences = {
                    throw java.io.IOException("materialization failed")
                })
                fail("Failed restore was accepted")
            } catch (_: java.io.IOException) { }
            assertEquals(unsaved, History.snapshot(ctx))
            assertEquals(HistoryFailure.WRITE, History.healthFailure)
            assertTrue(BackupManager.hasPending())
        } finally { if (file.exists()) check(file.setWritable(true, true)) }
    }
}
