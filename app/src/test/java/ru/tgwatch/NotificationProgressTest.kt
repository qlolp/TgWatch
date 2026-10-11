package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class NotificationProgressTest {
    @Test fun unknownAndOfflineOnlyHideProgress() {
        assertNull(NotificationProgress.percent(TimeStats(0, 0, 0, 90_000, 90_000, 0, 4, -1)))
    }
    @Test fun availabilityUsesTimeAndExcludesOfflineUnknown() {
        assertEquals(60, NotificationProgress.percent(TimeStats(60_000, 30_000, 10_000,
            900_000, 9_000_000, 30_000, 1, -1)))
    }
    @Test fun knownFullyDownStillShowsZero() {
        assertEquals(0, NotificationProgress.percent(TimeStats(0, 30_000, 0, 0, 90_000, 30_000, 1, -1)))
    }
}
