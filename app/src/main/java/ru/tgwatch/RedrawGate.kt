package ru.tgwatch

/**
 * Пропускает периодическую перерисовку, если видимое содержимое не изменилось.
 * Изменённый ключ проходит сразу; одинаковый — не чаще раза в [maxSilenceMs],
 * чтобы хост (шторка, лаунчер) не держал устаревшую копию бесконечно.
 */
internal class RedrawGate(private val maxSilenceMs: Long = MAX_SILENCE_MS) {
    private var lastKey: Any? = null
    private var lastAt = NEVER

    /** true — рисовать; ключ и время запоминаются. [elapsed] — монотонное время. */
    @Synchronized
    fun shouldDraw(key: Any, elapsed: Long): Boolean {
        val due = lastAt == NEVER || key != lastKey || elapsed < lastAt || elapsed - lastAt >= maxSilenceMs
        if (due) {
            lastKey = key
            lastAt = elapsed
        }
        return due
    }

    @Synchronized
    fun reset() {
        lastKey = null
        lastAt = NEVER
    }

    companion object {
        const val MAX_SILENCE_MS = 5 * 60_000L
        private const val NEVER = Long.MIN_VALUE
    }
}
