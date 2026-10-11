package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class EventLogStorageTest {
    @Test fun encodedLogsPreserveNewlinesEmptyStringsAndUnicode() {
        val rows = listOf("first\nsecond", "", "\r\n", "журнал 🙂", "  ")
        val encoded = EventLogStorage.encode(rows)
        val decoded = EventLogStorage.decode(encoded)
        assertEquals(rows, decoded)
        assertEquals(emptyList<String>(), EventLogStorage.decode(EventLogStorage.encode(emptyList())))
    }
    @Test fun legacyNewlineSeparatedLogsStillLoad() {
        assertEquals(listOf("first", "second"), EventLogStorage.decode("first\n\nsecond\n"))
    }
}
