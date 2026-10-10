package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class EventPreferenceMergeTest {
    @Test fun absentFlagsUseLegacySoundWithoutOptingIntoPartial() {
        for (event in listOf("TG_DOWN", "PARTIAL", "RECOVERY")) {
            assertFalse(EventPreferences.sound(event, emptyMap()))
        }
        val legacy = mapOf("event_sound" to "true", "quiet_start_minute" to "1395", "quiet_end_minute" to "525")
        assertTrue(EventPreferences.sound("TG_DOWN", legacy))
        assertTrue(EventPreferences.sound("RECOVERY", legacy))
        assertFalse(EventPreferences.sound("PARTIAL", legacy))
    }

    @Test fun perEventSoundOverridesStayIndependentOfLegacyAndOtherEvents() {
        val values = mapOf("event_sound" to "true", "sound_outage" to "false", "sound_partial" to "true")
        assertFalse(EventPreferences.sound("TG_DOWN", values))
        assertTrue(EventPreferences.sound("PARTIAL", values))
        assertTrue(EventPreferences.sound("RECOVERY", values))
        assertFalse(EventPreferences.sound("RECOVERY", values + ("sound_recovery" to "false")))
        assertTrue(EventPreferences.sound("PARTIAL", values + ("sound_recovery" to "false")))
    }

    @Test fun recoveryNotificationSharesLegacyKeyButOtherCategoriesHaveOwnKeys() {
        assertEquals("notify_recovery", EventPreferences.notifyKey("RECOVERY"))
        assertEquals("notify_outage", EventPreferences.notifyKey("TG_DOWN"))
        assertEquals("notify_partial", EventPreferences.notifyKey("PARTIAL"))
        assertEquals("sound_recovery", EventPreferences.soundKey("RECOVERY"))
        assertEquals("sound_outage", EventPreferences.soundKey("TG_DOWN"))
        assertEquals("sound_partial", EventPreferences.soundKey("PARTIAL"))
    }
}
