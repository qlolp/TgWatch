package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LastSuccessStoreTest {
    @Test fun failedDiagnosticWriteCannotRejectAPublishedOk() {
        val preferences = MuteTestPreferences(mapOf("last_success_at" to 1000L))
        preferences.writesSucceed = false
        val failure = runCatching { LastSuccessStore.record(preferences.context(), "OK", 2000L) }.exceptionOrNull()
        assertEquals("Diagnostic persistence must never turn a measured OK into UNKNOWN", null, failure)
    }

    @Test fun restoreClearStillRequiresDurablePersistence() {
        val preferences = MuteTestPreferences(mapOf("last_success_at" to 1000L))
        preferences.writesSucceed = false
        assertThrows(IllegalStateException::class.java) { LastSuccessStore.clear(preferences.context()) }
    }

    @Test fun successfulWritesRetainExactTimestampIncludingClockRollback() {
        val ctx = MuteTestPreferences().context()
        LastSuccessStore.record(ctx, "OK", 2000L)
        assertEquals(2000L, LastSuccessStore.lastSuccessAt(ctx))
        LastSuccessStore.record(ctx, "OK", 1000L)
        assertEquals(1000L, LastSuccessStore.lastSuccessAt(ctx))
        LastSuccessStore.record(ctx, "UNKNOWN", 3000L)
        assertEquals(1000L, LastSuccessStore.lastSuccessAt(ctx))
    }
}
