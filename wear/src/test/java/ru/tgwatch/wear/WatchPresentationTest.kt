package ru.tgwatch.wear

import org.junit.Assert.*
import org.junit.Test

class WatchPresentationTest {
    @Test fun missingStaleAndAmbientDescriptions() {
        val empty = WatchPresentation.from(null, 1_000, 10_000, russian = false)
        assertTrue(empty.stale)
        assertTrue(empty.contentDescription.contains("watch"))
        val value = WatchSnapshot("OK", 10_000, 1_000, 55, 3, 1)
        val fresh = WatchPresentation.from(value, 1_001, 10_001, russian = false)
        assertFalse(fresh.stale)
        assertEquals("OK", fresh.shortText)
        val old = WatchPresentation.from(value, 181_001, 190_001, russian = false)
        assertTrue(old.stale)
        assertFalse(old.shortText == "OK")
        assertTrue(old.contentDescription.contains("Last measured"))
        val rebooted = WatchPresentation.from(value.copy(checkedElapsedMs = 0), 1_001, 10_001, russian = false)
        assertTrue(rebooted.stale)
    }
    @Test fun unknownNeverClaimsGlobalOutageAndRussianIsReadable() {
        val value = WatchSnapshot("UNKNOWN", 10_000, 1_000, null, 3, 1)
        val frame = WatchPresentation.from(value, 1_001, 10_001, russian = true)
        assertTrue(frame.contentDescription.contains("часах"))
        assertFalse(frame.longText.contains("недоступен"))
    }
}
