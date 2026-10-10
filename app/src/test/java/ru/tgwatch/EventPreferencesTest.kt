package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class EventPreferencesTest {
    @Test fun oldSoundMigratesWithoutOptingIntoPartialAndIndependentOverridesWin() {
        val type = Class.forName("ru.tgwatch.EventPreferences")
        val instance = type.getField("INSTANCE").get(null)
        fun value(event: String, values: Map<String,String>): Boolean =
            type.getMethod("sound",String::class.java,Map::class.java).invoke(instance,event,values) as Boolean
        assertTrue(value("TG_DOWN",mapOf("event_sound" to "true")))
        assertTrue(value("RECOVERY",mapOf("event_sound" to "true")))
        assertFalse(value("PARTIAL",mapOf("event_sound" to "true")))
        assertFalse(value("TG_DOWN",mapOf("event_sound" to "true","sound_outage" to "false")))
        assertTrue(value("RECOVERY",mapOf("event_sound" to "true","sound_outage" to "false")))
        assertTrue(value("PARTIAL",mapOf("sound_partial" to "true")))
    }
}
