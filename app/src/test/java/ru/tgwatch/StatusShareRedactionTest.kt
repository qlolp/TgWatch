package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class StatusShareRedactionTest {
    @Test fun immutablePublicSnapshotCannotCarryPrivateSourceStrings() {
        val source = mutableListOf(TargetStatus.OK, TargetStatus.UNKNOWN)
        val snapshot = StatusShareSnapshot(TargetId.CUSTOM, TargetStatus.OK, 1000, 1000,
            180, false, true, 20, null, source, 7, localeTag = "ru")
        source[0] = TargetStatus.DOWN
        assertEquals(listOf(TargetStatus.OK, TargetStatus.UNKNOWN), snapshot.chart)
        assertEquals(setOf("localeTag"), StatusShareSnapshot::class.java.declaredFields
            .filter { it.type == String::class.java }.map { it.name }.toSet())
        assertEquals("ru", snapshot.localeTag)
        assertNull(snapshot.coveragePercent)
    }
    @Test fun localeMetadataIsCanonicalBoundedAndCannotCarryAnAddressOrToken() {
        for (bad in listOf("", "und", "RU", "https://private.example/token", "en\nBearer secret", "a".repeat(100))) {
            try {
                StatusShareSnapshot(TargetId.TELEGRAM, TargetStatus.UNKNOWN, 1000, 0,
                    0, true, false, null, null, emptyList(), 0, localeTag = bad)
                fail("Invalid locale tag accepted")
            } catch (_: IllegalArgumentException) { }
        }
        for (tag in listOf("en", "ru", "ru-RU", "en-US")) assertTrue(StatusShareSnapshot.validLocaleTag(tag))
    }
}
