package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorReasonTest {
    private class ActivityNotFoundException(message: String) : RuntimeException(message)

    @Test fun mapsKnownExceptionClassesWithoutExposingMessages() {
        assertEquals(ErrorReason.Kind.PERMISSION, ErrorReason.kindOf(SecurityException("token")))
        assertEquals(ErrorReason.Kind.NO_APP, ErrorReason.kindOf(ActivityNotFoundException("url")))
        assertEquals(ErrorReason.Kind.STORAGE, ErrorReason.kindOf(java.io.IOException("path")))
        assertEquals(ErrorReason.Kind.STATE, ErrorReason.kindOf(IllegalStateException("not running")))
        assertEquals(ErrorReason.Kind.UNKNOWN, ErrorReason.kindOf(RuntimeException("boom")))
    }

    @Test fun walksCauseChainWithBoundedDepth() {
        assertEquals(ErrorReason.Kind.STORAGE, ErrorReason.kindOf(
            RuntimeException("outer", RuntimeException("mid", java.io.IOException("inner")))))
        // Four wrappers still expose the storage cause inside the walk window.
        assertEquals(ErrorReason.Kind.STORAGE, ErrorReason.kindOf(
            RuntimeException("1", RuntimeException("2", RuntimeException("3",
                RuntimeException("4", java.io.IOException("in-window")))))))
        // Five wrappers exhaust the walk window before the storage cause.
        assertEquals(ErrorReason.Kind.UNKNOWN, ErrorReason.kindOf(
            RuntimeException("1", RuntimeException("2", RuntimeException("3",
                RuntimeException("4", RuntimeException("5", java.io.IOException("late"))))))))
    }

    @Test fun textIsStableForEveryKindAndNeverEmbedsThrowableMessage() {
        assertEquals(TextKey.ERROR_REASON_PERMISSION, ErrorReason.text(SecurityException("token")).key)
        assertEquals(TextKey.ERROR_REASON_NO_APP, ErrorReason.text(ActivityNotFoundException("url")).key)
        assertEquals(TextKey.ERROR_REASON_STORAGE, ErrorReason.text(java.io.IOException("path")).key)
        assertEquals(TextKey.ERROR_REASON_STATE, ErrorReason.text(IllegalStateException("x")).key)
        assertEquals(TextKey.ERROR_REASON_UNKNOWN, ErrorReason.text(RuntimeException("boom")).key)
        for (kind in ErrorReason.Kind.entries) {
            val value = ErrorReason.text(kind)
            assertTrue("No raw exception payload in $kind", value.args.isEmpty())
        }
    }
}
