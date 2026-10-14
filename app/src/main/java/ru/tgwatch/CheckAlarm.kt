package ru.tgwatch

/**
 * Будильник, который будит телефон к следующей проверке.
 *
 * При включённом экране процесс не спит: проверку запускает обычный таймер, и следующая
 * постановка заменяет будильник раньше, чем он сработает. Поэтому будильник ставится с
 * запасом [padMs] — без него он срабатывал каждый интервал и заново поднимал службу.
 * Когда экран гаснет, запас снимается, чтобы первая проверка во сне не опоздала.
 */
internal class CheckAlarm(
    private val arm: (atElapsed: Long) -> Unit,
    private val disarm: () -> Unit,
    /** Читается под той же блокировкой, что и [screenOff], чтобы не пропустить погасший экран. */
    private val interactive: () -> Boolean,
    private val padMs: Long = INTERACTIVE_PAD_MS,
    private val minDelayMs: Long = MIN_DELAY_MS,
) {
    private var dueElapsed = 0L
    private var padded = false

    @Synchronized
    fun schedule(nowElapsed: Long, delayMs: Long) {
        val due = nowElapsed + delayMs
        val pad = interactive()
        arm(if (pad) due + padMs else due)
        dueElapsed = due
        padded = pad
    }

    /** Экран погас: вернуть будильник с запасом к точному сроку (но не раньше [minDelayMs]). */
    @Synchronized
    fun screenOff(nowElapsed: Long) {
        if (!padded) return
        padded = false
        arm(dueElapsed.coerceAtLeast(nowElapsed + minDelayMs))
    }

    @Synchronized
    fun cancel() {
        padded = false
        disarm()
    }

    companion object {
        /** Больше самой долгой проверки (30 с до принудительной отмены), чтобы таймер успел первым. */
        const val INTERACTIVE_PAD_MS = 60_000L
        const val MIN_DELAY_MS = 5_000L
    }
}
