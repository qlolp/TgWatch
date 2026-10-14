package ru.tgwatch

import android.content.Context
import android.util.Log

/**
 * Неудачный запуск или команда службы из виджета и плитки раньше пропадали молча.
 * Записываем в журнал событий только источник и класс исключения: без адресов,
 * токенов и текста ошибки, который может их содержать.
 */
internal object LaunchFailures {
    enum class Source(val ru: String, val en: String) {
        WIDGET("Виджет", "Widget"),
        TILE("Плитка", "Tile"),
    }

    fun message(source: Source, e: Throwable): String {
        val kind = e.javaClass.simpleName.ifEmpty { "Exception" }
        return Lang.t("${source.ru}: не удалось обратиться к мониторингу ($kind)",
            "${source.en}: could not reach monitoring ($kind)")
    }

    @Suppress("TooGenericExceptionCaught") // The journal is best-effort; any storage failure only logs its class.
    fun record(ctx: Context, source: Source, e: Throwable) {
        Log.w("TgWatch", "${source.name} launch failed: ${e.javaClass.simpleName}")
        try {
            EventLog.add(ctx, message(source, e))
        } catch (logError: Exception) {
            Log.w("TgWatch", "event log: ${logError.javaClass.simpleName}")
        }
    }
}
