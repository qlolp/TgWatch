package ru.tgwatch

enum class SlowDisplayAction { SHOW, CLEAR, NONE }

data class SlowDisplayState(val active: Boolean, val enabled: Boolean, val muted: Boolean,
    val quiet: Boolean, val running: Boolean) {
    val blocked get() = !enabled || muted || quiet || !running
    val canShow get() = active && !blocked
}

/** Measurement facts survive quiet/mute; only actual displays consume the presentation cooldown. */
class SlowAlertPresentation(private val cooldownMs: Long = SlowAlert.COOLDOWN_MS) {
    private var visible = false
    private var shownAt: Long? = null

    @Synchronized fun reconcile(state: SlowDisplayState, nowElapsed: Long): SlowDisplayAction {
        val allowed = state.canShow
        val action = when {
            !allowed && visible -> SlowDisplayAction.CLEAR
            allowed && !visible && cooled(nowElapsed) -> SlowDisplayAction.SHOW
            else -> SlowDisplayAction.NONE
        }
        if (action == SlowDisplayAction.CLEAR) visible = false
        if (action == SlowDisplayAction.SHOW) { visible = true; shownAt = nowElapsed }
        return action
    }

    private fun cooled(now: Long): Boolean {
        val previous = shownAt
        return previous == null || now >= previous && now - previous >= cooldownMs
    }
}
