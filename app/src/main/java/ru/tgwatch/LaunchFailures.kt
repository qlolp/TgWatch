package ru.tgwatch

import android.content.Context
import android.util.Log

/**
 * Неудачный запуск или команда службы из виджета и плитки раньше пропадали молча.
 * Записываем в журнал событий только источник и класс исключения: без адресов,
 * токенов и текста ошибки, который может их содержать.
 */
internal object LaunchFailures {
    enum class Source { WIDGET, TILE }

    fun message(source: Source, e: Throwable): TextValue {
        val kind = e.javaClass.simpleName.ifEmpty { "Exception" }
        val label = when (source) {
            Source.WIDGET -> textOf(TextKey.COPY_LAUNCH_FAILURES_WIDGET)
            Source.TILE -> textOf(TextKey.COPY_LAUNCH_FAILURES_TILE)
        }
        return textOf(TextKey.COPY_LAUNCH_FAILURES_COULD_NOT_REACH_MONITORING, textArgument(label), textArgument(kind))
    }

    @Suppress("TooGenericExceptionCaught") // The journal is best-effort; any storage failure only logs its class.
    fun record(ctx: Context, source: Source, e: Throwable) {
        Log.w("TgWatch", "${source.name} launch failed: ${e.javaClass.simpleName}")
        try {
            EventLog.add(ctx, AndroidText.resolve(ctx, message(source, e)))
        } catch (logError: Exception) {
            Log.w("TgWatch", "event log: ${logError.javaClass.simpleName}")
        }
    }
}
