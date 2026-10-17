package ru.tgwatch

/**
 * «Telegram тормозит»: Telegram отвечает, но даже самый быстрый ответ MTProto несколько проверок
 * подряд медленнее порога. Снимается, только когда ответ заметно быстрее порога (гистерезис),
 * и повторяется не чаще [cooldownMs], чтобы уведомление не мигало на границе.
 * UNKNOWN и проверки без задержки прерывают streak; подтверждённый сбой снимает предупреждение.
 */
class SlowAlert(
    private val thresholdMs: Long = THRESHOLD_MS,
    private val clearBelowMs: Long = CLEAR_BELOW_MS,
    private val checksNeeded: Int = CHECKS_NEEDED,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    enum class Action { NONE, ALERT, CLEAR }

    private var streak = 0
    private var lastAlertMono = -1L
    var active = false
        private set

    @Synchronized
    fun onCheck(status: String, latencyMs: Long, nowMono: Long): Action = when {
        status == "UNKNOWN" -> { streak = 0; Action.NONE }
        status != "OK" -> {
            streak = 0
            clear()
        }
        latencyMs < 0 -> { streak = 0; Action.NONE }
        latencyMs < thresholdMs -> {
            streak = 0
            if (latencyMs < clearBelowMs) clear() else Action.NONE
        }
        else -> slowCheck(nowMono)
    }

    private fun slowCheck(nowMono: Long): Action {
        streak++
        val cooled = lastAlertMono < 0 || nowMono - lastAlertMono >= cooldownMs
        if (active || streak < checksNeeded || !cooled) return Action.NONE
        active = true
        lastAlertMono = nowMono
        return Action.ALERT
    }

    private fun clear(): Action = if (active) {
        active = false
        Action.CLEAR
    } else {
        Action.NONE
    }

    companion object {
        const val THRESHOLD_MS = 1_000L
        const val CLEAR_BELOW_MS = 600L
        const val CHECKS_NEEDED = 3
        const val COOLDOWN_MS = 30 * 60_000L
    }
}
