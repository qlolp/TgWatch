package ru.tgwatch

enum class AlarmPattern(private val waveform: LongArray) {
    STANDARD(longArrayOf(0, 700, 300, 700, 300, 700)),
    SHORT(longArrayOf(0, 200, 100, 200)),
    LONG(longArrayOf(0, 1200));

    /** Follows the current UI language, so it is computed rather than stored. */
    val title: TextValue get() = TextValue(when (this) {
        STANDARD -> TextKey.PATTERN_STANDARD
        SHORT -> TextKey.PATTERN_SHORT
        LONG -> TextKey.PATTERN_LONG
    })

    fun timings(): LongArray = waveform.copyOf()
}
