package ru.tgwatch

data class TargetPowerState(val screenOn: Boolean, val powerSave: Boolean,
    val batteryPercent: Int?, val charging: Boolean) {
    init { require(batteryPercent == null || batteryPercent in 0..FULL_PERCENT) }
    private companion object { const val FULL_PERCENT = 100 }
}

/** Runtime limits are separate from the portable user-selected interval. */
object TargetPowerPolicy {
    const val MAX_EFFECTIVE_SEC = 600
    const val SCREEN_OFF_SEC = 300
    private const val LOW_BATTERY_PERCENT = 15

    fun intervalSec(configured: Int, enabled: Boolean, state: TargetPowerState): Int {
        require(configured in TargetDefinition.MIN_TARGET_SEC..TargetDefinition.MAX_TARGET_SEC)
        val minimum = when {
            !enabled -> configured
            state.powerSave || !state.charging && state.batteryPercent?.let { it <= LOW_BATTERY_PERCENT } == true ->
                MAX_EFFECTIVE_SEC
            !state.screenOn -> SCREEN_OFF_SEC
            else -> configured
        }
        return maxOf(configured, minimum)
    }
}
