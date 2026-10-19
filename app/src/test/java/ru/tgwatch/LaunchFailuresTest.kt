package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchFailuresTest {
    @Test fun messageNamesOnlyTheSourceAndExceptionClass() {
        val secret = "https://hooks.example.invalid/token=abc"
        val message = LaunchFailures.message(LaunchFailures.Source.WIDGET, IllegalStateException(secret))
        assertTrue(message.allKeys().contains(TextKey.COPY_LAUNCH_FAILURES_WIDGET))
        assertTrue(message.allArguments().contains(TextArgument.Text("IllegalStateException")))
        assertFalse("Exception text may contain addresses or tokens", message.allArguments().any { it is TextArgument.Text && "token" in it.value })
    }

    @Test fun anonymousExceptionClassesStillProduceALabel() {
        val anonymous = object : RuntimeException() {}
        val message = LaunchFailures.message(LaunchFailures.Source.TILE, anonymous)
        assertTrue(message.allKeys().contains(TextKey.COPY_LAUNCH_FAILURES_TILE))
        assertTrue(message.allArguments().contains(TextArgument.Text("Exception")))
    }
}
