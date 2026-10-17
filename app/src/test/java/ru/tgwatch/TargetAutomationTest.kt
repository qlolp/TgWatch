package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class TargetAutomationTest {
    @Test fun legacyActionsAlwaysSelectTelegramEvenWithAnUnrelatedTargetExtra() {
        for (extra in listOf(null, "matrix", "https://private.example")) {
            val check = checkNotNull(TargetAutomation.parse(AutomationPolicy.ACTION_CHECK_NOW, extra))
            assertEquals(TargetId.TELEGRAM, check.targetId)
            assertEquals(1, check.apiVersion)
            assertTrue(check.checkNow)
            val read = checkNotNull(TargetAutomation.parse(AutomationPolicy.ACTION_GET_STATE, extra))
            assertEquals(TargetId.TELEGRAM, read.targetId)
            assertFalse(read.checkNow)
        }
    }

    @Test fun v2UsesOnlyExplicitAllowlistedTargetsAndActions() {
        for (id in TargetId.entries) {
            val request = checkNotNull(TargetAutomation.parse(TargetAutomation.ACTION_CHECK_TARGET_NOW, id.wire))
            assertEquals(id, request.targetId)
            assertEquals(2, request.apiVersion)
            assertTrue(request.checkNow)
            assertFalse(checkNotNull(TargetAutomation.parse(TargetAutomation.ACTION_GET_TARGET_STATE, id.wire)).checkNow)
        }
        for (invalid in listOf(null, "", "MATRIX", "../matrix", "https://private.example"))
            assertNull(TargetAutomation.parse(TargetAutomation.ACTION_GET_TARGET_STATE, invalid))
        assertNull(TargetAutomation.parse("GET_STATE", "telegram"))
        assertNull(TargetAutomation.parse(null, "telegram"))
    }

    @Test fun v2DoesNotBroadenBearerAuthorization() {
        assertTrue(AutomationPolicy.validAction(TargetAutomation.ACTION_GET_TARGET_STATE))
        assertTrue(AutomationPolicy.validAction(TargetAutomation.ACTION_CHECK_TARGET_NOW))
        val token = "a".repeat(AutomationPolicy.TOKEN_LENGTH)
        for (invalid in listOf(null, "", "b".repeat(64), token + "a"))
            assertFalse(AutomationPolicy.authorized(true, token, invalid))
        assertFalse(AutomationPolicy.authorized(false, token, token))
        assertTrue(AutomationPolicy.authorized(true, token, token))
    }

    @Test fun targetNotificationIdsNeverAliasAnotherTargetOrLegacyNotice() {
        val events = listOf("outage", "partial", "recovery", "slow")
        val ids = TargetId.entries.flatMap { id -> events.map { TargetEventNotifications.id(id, it) } }
        assertEquals(TargetId.entries.size * events.size, ids.toSet().size)
        assertEquals(EventNotifications.OUTAGE_ID, TargetEventNotifications.id(TargetId.TELEGRAM, "outage"))
        assertEquals(EventNotifications.RECOVERY_ID, TargetEventNotifications.id(TargetId.TELEGRAM, "recovery"))
        assertEquals(EventNotifications.PARTIAL_ID, TargetEventNotifications.id(TargetId.TELEGRAM, "partial"))
        assertEquals(EventNotifications.SLOW_ID, TargetEventNotifications.id(TargetId.TELEGRAM, "slow"))
        assertTrue(TargetId.entries.filter { it != TargetId.TELEGRAM }.all { id ->
            events.none { TargetEventNotifications.id(id, it) in 1..100 }
        })
        assertThrows(IllegalArgumentException::class.java) { TargetEventNotifications.id(TargetId.MATRIX, "unknown") }
    }
}
