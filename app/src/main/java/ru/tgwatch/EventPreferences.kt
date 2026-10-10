package ru.tgwatch

/** Legacy global sound is only a fallback, so changing one event cannot change another. */
object EventPreferences {
    fun soundKey(event: String) = when(event) {
        "TG_DOWN" -> "sound_outage"; "PARTIAL" -> "sound_partial"; "RECOVERY" -> "sound_recovery"
        else -> error("Unknown event")
    }
    fun notifyKey(event: String) = when(event) {
        "TG_DOWN" -> "notify_outage"; "PARTIAL" -> "notify_partial"; "RECOVERY" -> "notify_recovery"
        else -> error("Unknown event")
    }
    fun sound(event: String, values: Map<String,String>): Boolean =
        values[soundKey(event)]?.toBooleanStrictOrNull() ?: (event != "PARTIAL" && values["event_sound"] == "true")
}
