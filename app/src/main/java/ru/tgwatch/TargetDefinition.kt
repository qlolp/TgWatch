package ru.tgwatch

enum class TargetId(val wire: String) {
    TELEGRAM("telegram"), MATRIX("matrix"), WHATSAPP("whatsapp"), CUSTOM("custom");
    companion object { fun fromWire(value: String): TargetId? = entries.firstOrNull { it.wire == value } }
}

enum class TargetStatus {
    OK, PARTIAL, DOWN, NO_NETWORK, UNKNOWN;
    companion object {
        fun fromWire(value: String): TargetStatus? =
            if (value == "TG_DOWN") DOWN else entries.firstOrNull { it.name == value }
    }
}

data class TargetAlertSettings(val notifyOutage: Boolean = true, val notifyRecovery: Boolean = true,
    val notifySlow: Boolean = false, val slowThresholdMs: Long = DEFAULT_SLOW_MS) {
    init { require(slowThresholdMs in MIN_SLOW_MS..MAX_SLOW_MS && slowThresholdMs % SLOW_STEP_MS == 0L) }
    companion object {
        const val MIN_SLOW_MS = 500L
        const val MAX_SLOW_MS = 5_000L
        const val SLOW_STEP_MS = 100L
        const val DEFAULT_SLOW_MS = 1_000L
    }
}

/** Public configuration only: endpoint addresses are held separately in credential storage. */
data class TargetDefinition(val id: TargetId, val enabled: Boolean, val intervalSec: Int,
    val requiresEndpoint: Boolean, val alerts: TargetAlertSettings) {
    init {
        val range = if (id == TargetId.TELEGRAM) MIN_TELEGRAM_SEC..MAX_TELEGRAM_SEC else MIN_TARGET_SEC..MAX_TARGET_SEC
        require(intervalSec in range)
        require(id !in setOf(TargetId.TELEGRAM, TargetId.WHATSAPP) || !requiresEndpoint)
        require(id != TargetId.CUSTOM || requiresEndpoint)
    }
    companion object {
        const val MIN_TELEGRAM_SEC = 10
        const val MAX_TELEGRAM_SEC = 120
        const val DEFAULT_TELEGRAM_SEC = 30
        const val MIN_TARGET_SEC = 60
        const val MAX_TARGET_SEC = 300
        fun default(id: TargetId) = TargetDefinition(id, id == TargetId.TELEGRAM,
            if (id == TargetId.TELEGRAM) DEFAULT_TELEGRAM_SEC else MIN_TARGET_SEC,
            id == TargetId.CUSTOM, TargetAlertSettings(notifySlow = id == TargetId.TELEGRAM))
    }
}

data class TargetConfiguration(val definition: TargetDefinition, val generation: Long = 0L,
    val endpointRevision: Long = 0L) {
    init { require(generation >= 0L && endpointRevision >= 0L) }
}

data class TargetObservation(val row: Observation, val endpointRevision: Long = 0L) {
    init { require(endpointRevision >= 0L) }
}
