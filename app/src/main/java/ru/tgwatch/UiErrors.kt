package ru.tgwatch

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * Toast failures without raw exception text.
 *
 * Messages may contain URLs or tokens, so the UI shows only [ErrorReason]
 * and logs the exception class name for local diagnosis.
 */
internal object UiErrors {
    fun show(context: Context, format: Int, error: Throwable, long: Boolean = true) {
        Log.w("TgWatch", "ui failure: ${error.javaClass.simpleName}")
        val reason = AndroidText.resolve(context, ErrorReason.text(error))
        Toast.makeText(context, context.renderString(format, reason),
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
