package ru.tgwatch

internal data class TargetServiceCommand(val action: String?, val targetId: TargetId,
    val stopTarget: Boolean = false, val stopAll: Boolean = false, val ignored: Boolean = false)

/** Missing goal IDs belong to historical Telegram commands; invalid IDs never select a goal. */
internal object TargetServiceCommands {
    fun expectedIntervalSec(telegramActualSec: Int, definitions: List<TargetDefinition>,
        secondaryIntervals: Map<TargetId, Int> = emptyMap()): Int =
        definitions.filter { it.enabled }.minOfOrNull {
            if (it.id == TargetId.TELEGRAM) telegramActualSec else secondaryIntervals[it.id] ?: it.intervalSec
        } ?: TargetDefinition.DEFAULT_TELEGRAM_SEC

    fun route(action: String?, rawId: String?, enabled: Set<TargetId>,
        regionalEnabled: Boolean = false): TargetServiceCommand {
        val id = if (rawId == null) TargetId.TELEGRAM else TargetId.fromWire(rawId)
        if (id == null) return TargetServiceCommand(null, TargetId.TELEGRAM, ignored = true)
        return when (action) {
            MonitorService.ACTION_STOP -> {
                val last = (enabled - id).isEmpty() && !regionalEnabled
                TargetServiceCommand(if (last) action else MonitorService.ACTION_SETTINGS, id, true, last)
            }
            MonitorService.ACTION_CHECK_NOW -> TargetServiceCommand(action.takeIf { id in enabled }, id)
            else -> TargetServiceCommand(action, id)
        }
    }
}
