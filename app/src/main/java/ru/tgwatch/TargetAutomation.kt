package ru.tgwatch

data class TargetAutomationRequest(val targetId: TargetId, val apiVersion: Int, val checkNow: Boolean)

/** Routing only; the exported receiver applies the existing bearer and lifecycle guards first. */
object TargetAutomation {
    const val ACTION_GET_TARGET_STATE = "ru.tgwatch.automation.GET_TARGET_STATE"
    const val ACTION_CHECK_TARGET_NOW = "ru.tgwatch.automation.CHECK_TARGET_NOW"
    const val EXTRA_TARGET_ID = "target_id"

    fun parse(action: String?, targetId: String?): TargetAutomationRequest? = when (action) {
        AutomationPolicy.ACTION_GET_STATE -> TargetAutomationRequest(TargetId.TELEGRAM, 1, false)
        AutomationPolicy.ACTION_CHECK_NOW -> TargetAutomationRequest(TargetId.TELEGRAM, 1, true)
        ACTION_GET_TARGET_STATE, ACTION_CHECK_TARGET_NOW -> TargetId.fromWire(targetId.orEmpty())?.let {
            TargetAutomationRequest(it, 2, action == ACTION_CHECK_TARGET_NOW)
        }
        else -> null
    }
}
