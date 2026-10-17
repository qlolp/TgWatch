package ru.tgwatch

/** Stable identities are independent of selected screen, timestamps and credential endpoint text. */
object TargetEventNotifications {
    private const val BASE_ID = 2_000
    private const val TARGET_STRIDE = 16
    private val events = listOf("outage", "partial", "recovery", "slow")

    fun id(targetId: TargetId, event: String): Int {
        val eventIndex = events.indexOf(event)
        require(eventIndex >= 0)
        return if (targetId == TargetId.TELEGRAM) when (event) {
            "outage" -> EventNotifications.OUTAGE_ID
            "partial" -> EventNotifications.PARTIAL_ID
            "recovery" -> EventNotifications.RECOVERY_ID
            else -> EventNotifications.SLOW_ID
        } else BASE_ID + targetId.ordinal * TARGET_STRIDE + eventIndex
    }
}
