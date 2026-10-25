package ru.tgwatch

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.tgwatch.probe.HttpProbeCondition

@RunWith(AndroidJUnit4::class)
class TargetSlotsIntegrationTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = TargetId.CUSTOM_2
    private val address = "https://second.private.example/health"
    private val condition = HttpProbeCondition(204, "second-field", "second-value")
    private val credentials get() = ctx.getSharedPreferences(TargetPreferences.PRIVATE_FILE, Context.MODE_PRIVATE)
    private lateinit var backup: TargetBackupSnapshot
    private lateinit var settings: Map<String, *>
    private lateinit var privateSettings: Map<String, *>

    @Before fun isolate() {
        HistoryTestStorage.awaitReady()
        settings = Prefs.sp(ctx).all.toMap()
        privateSettings = credentials.all.toMap()
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        TargetPersistence.initialize(ctx)
        backup = BackupManager.snapshotTargets(ctx)
        for (slot in TargetId.custom.filter { it.isOptional }) TargetSlots.remove(ctx, slot)
    }

    @After fun restore() {
        TargetConfigTransactions.retry(ctx, id)
        Prefs.setEnabled(ctx, false)
        MonitorService.stopAndAwait(ctx)
        if (BackupManager.hasPending()) BackupManager.recoverPending(ctx)
        if (::backup.isInitialized) BackupManager.restoreTargets(ctx, backup)
        if (::privateSettings.isInitialized) restore(credentials, privateSettings)
        if (::settings.isInitialized) restore(Prefs.sp(ctx), settings)
        EventLog.invalidate()
    }

    @Test fun addedSlotStartsEmptyAndRemovalWipesAddressHistoryAndAlerts() {
        assertFalse(TargetSlots.present(ctx, id))
        assertFalse(id in TargetSlots.visible(ctx))
        assertEquals(id, TargetSlots.free(ctx))
        assertFalse(TargetPreferences.storedDefinition(ctx, id).enabled)
        // A stale address left by an older build must not reappear in the new goal.
        assertTrue(credentials.edit().putString(id.wire, "https://stale.example").commit())
        assertTrue(TargetSlots.add(ctx, id))
        assertFalse(TargetSlots.add(ctx, id))
        assertTrue(TargetSlots.present(ctx, id))
        assertEquals(TargetId.CUSTOM_3, TargetSlots.free(ctx))
        assertNull(credentials.getString(id.wire, null))
        assertTrue(TargetPersistence.storage().read(id).isEmpty())

        assertTrue(TargetSettingWrites.endpoint(ctx, id, address, condition = condition))
        assertEquals(PrivateProbeConfig(address, condition), TargetPreferences.configuration(ctx, id))
        assertTrue(TargetSettingWrites.definition(ctx, TargetPreferences.storedDefinition(ctx, id)
            .copy(alerts = TargetAlertSettings(true, false, true, 3_000))))
        assertTrue(Prefs.sp(ctx).contains(TargetPreferences.key(id, "notify_recovery")))
        val now = System.currentTimeMillis()
        val revision = TargetPreferences.endpointRevision(ctx, id)
        HistorySessions.queue(ctx).exclusive { TargetPersistence.storage().replaceRows(id,
            listOf(TargetObservation(Observation(now - 2_000, now - 1_000, "DOWN"), revision))) }.get()
        assertTrue(Prefs.sp(ctx).edit().putString(MaintenanceWindows.key(id), "[]").commit())
        // The first custom goal and its history are untouched by the second one.
        val first = TargetPersistence.storage().read(TargetId.CUSTOM)

        TargetPreferences.stop(ctx, id)
        assertTrue(TargetSlots.remove(ctx, id))
        assertFalse(TargetSlots.present(ctx, id))
        assertNull(credentials.getString(id.wire, null))
        assertTrue(TargetPersistence.storage().read(id).isEmpty())
        assertTrue(TargetPreferences.endpointRevision(ctx, id) > revision)
        assertFalse(TargetPreferences.storedDefinition(ctx, id).enabled)
        assertFalse(Prefs.sp(ctx).contains(MaintenanceWindows.key(id)))
        assertFalse(Prefs.sp(ctx).contains(TargetPreferences.key(id, "notify_recovery")))
        assertEquals(first, TargetPersistence.storage().read(TargetId.CUSTOM))
        assertFalse(TargetSlots.remove(ctx, id))
        assertFalse(TargetSlots.remove(ctx, TargetId.CUSTOM))
    }

    @Test fun backupCarriesOnlyAddedSlotsAndNeverTheirPrivateConfiguration() {
        assertEquals(4, version(BackupManager.snapshotTargets(ctx)))
        assertTrue(BackupManager.snapshotTargets(ctx).targets.none { it.id.isOptional })
        assertTrue(TargetSlots.add(ctx, id))
        assertTrue(TargetSettingWrites.endpoint(ctx, id, address, condition = condition))
        val now = System.currentTimeMillis()
        val revision = TargetPreferences.endpointRevision(ctx, id)
        HistorySessions.queue(ctx).exclusive { TargetPersistence.storage().replaceRows(id,
            listOf(TargetObservation(Observation(now - 2_000, now - 1_000, "OK", 40), revision))) }.get()

        val withSlot = BackupManager.snapshotTargets(ctx)
        assertEquals(listOf(id), withSlot.targets.map { it.id }.filter { it.isOptional })
        assertEquals(5, version(withSlot))
        val exports = listOf(TargetBackupCodec.encode(withSlot).toString(Charsets.UTF_8),
            LocalDiagnostics.snapshot(ctx).toJson(),
            StatusShareDocument.prepare(ctx, StatusShareSnapshot.capture(ctx, id)).text)
        for (value in exports) for (secret in listOf(address, "second-field", "second-value"))
            assertFalse("Private configuration escaped an export", value.contains(secret))

        // Restoring a backup without the slot removes it together with its address.
        BackupManager.restoreTargets(ctx, backup)
        assertFalse(TargetSlots.present(ctx, id))
        assertNull(credentials.getString(id.wire, null))
        assertTrue(TargetPersistence.storage().read(id).isEmpty())

        // Restoring the backup with the slot brings back its history, stopped and waiting for an address.
        BackupManager.restoreTargets(ctx, withSlot)
        assertTrue(TargetSlots.present(ctx, id))
        assertEquals(withSlot.observations.getValue(id), TargetPersistence.storage().read(id))
        assertFalse(TargetPreferences.storedDefinition(ctx, id).enabled)
        assertNull(credentials.getString(id.wire, null))
        assertTrue(Prefs.sp(ctx).getBoolean(TargetPreferences.key(id, "endpoint_review"), false))
    }

    @Test fun reopenedStorageKeepsSlotHistoryAndUnusedSlotsStayEmpty() {
        assertTrue(TargetSlots.add(ctx, id))
        val now = System.currentTimeMillis()
        val rows = listOf(TargetObservation(Observation(now - 2_000, now - 1_000, "OK", 40),
            TargetPreferences.endpointRevision(ctx, id)))
        HistorySessions.queue(ctx).exclusive { TargetPersistence.storage().replaceRows(id, rows) }.get()
        // A fresh storage instance stands in for the process restarting.
        val reopened = TargetStorage.open(File(ctx.createDeviceProtectedStorageContext().filesDir, "targets-v1"))
        assertEquals(rows, reopened.read(id))
        assertTrue(reopened.read(TargetId.CUSTOM_5).isEmpty())
        assertEquals(TargetId.entries.toSet(), reopened.snapshot().observations.keys)
    }

    private fun version(snapshot: TargetBackupSnapshot): Int =
        JSONObject(TargetBackupCodec.encode(snapshot).toString(Charsets.UTF_8)).getInt("formatVersion")

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val edit = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
        } }
        check(edit.commit())
    }
}
