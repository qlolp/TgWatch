package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetSettingsFenceTest {
    @Test fun urgentStopInvalidatesPublicationsWithoutStrandingAnActiveSettingsOperation() {
        val fence = TargetSettingsFence()
        val token = checkNotNull(fence.begin(TargetId.CUSTOM))
        fence.bump(TargetId.CUSTOM)
        assertTrue(fence.snapshot(TargetId.CUSTOM).revision > token.revision)
        assertTrue(fence.snapshot(TargetId.CUSTOM).changing)
        assertTrue(fence.finish(token))
        assertFalse(fence.snapshot(TargetId.CUSTOM).changing)
    }
    @Test fun changingOneTargetNeverChangesAnotherTargetsAdmission() {
        val fence = TargetSettingsFence()
        val telegram = fence.snapshot(TargetId.TELEGRAM)
        val before = fence.snapshot(TargetId.CUSTOM)
        val token = checkNotNull(fence.begin(TargetId.CUSTOM))
        assertTrue(fence.snapshot(TargetId.CUSTOM).changing)
        assertTrue(fence.snapshot(TargetId.CUSTOM).revision > before.revision)
        assertEquals(telegram, fence.snapshot(TargetId.TELEGRAM))
        assertNull(fence.begin(TargetId.CUSTOM))
        assertTrue(fence.finish(token))
        assertFalse(fence.snapshot(TargetId.CUSTOM).changing)
        assertTrue(fence.snapshot(TargetId.CUSTOM).revision > token.revision)
    }

    @Test fun lateCompletionCannotCloseANewerSettingsTransaction() {
        val fence = TargetSettingsFence()
        val old = checkNotNull(fence.begin(TargetId.MATRIX))
        assertTrue(fence.finish(old))
        val current = checkNotNull(fence.begin(TargetId.MATRIX))
        assertFalse(fence.finish(old))
        assertTrue(fence.snapshot(TargetId.MATRIX).changing)
        assertTrue(fence.finish(current))
    }
}
