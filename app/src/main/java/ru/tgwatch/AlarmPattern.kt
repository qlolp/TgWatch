package ru.tgwatch

enum class AlarmPattern(private val waveform: LongArray) {
    STANDARD(longArrayOf(0, 700, 300, 700, 300, 700)),
    SHORT(longArrayOf(0, 200, 100, 200)),
    LONG(longArrayOf(0, 1200));

    /** Follows the current UI language, so it is computed rather than stored. */
    val title: String get() = when (this) {
        STANDARD -> Lang.t("Тройной", "Triple")
        SHORT -> Lang.t("Короткий", "Short")
        LONG -> Lang.t("Длинный", "Long")
    }

    fun timings(): LongArray = waveform.copyOf()
}
