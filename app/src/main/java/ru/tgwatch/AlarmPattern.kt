package ru.tgwatch

enum class AlarmPattern(val title: String, private val waveform: LongArray) {
    STANDARD("Тройной", longArrayOf(0, 700, 300, 700, 300, 700)),
    SHORT("Короткий", longArrayOf(0, 200, 100, 200)),
    LONG("Длинный", longArrayOf(0, 1200));

    fun timings(): LongArray = waveform.copyOf()
}
