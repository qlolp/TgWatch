package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LaunchFailuresTest {
    @Test fun messageNamesOnlyTheSourceAndExceptionClass() {
        val secret = "https://hooks.example.invalid/token=abc"
        val message = LaunchFailures.message(LaunchFailures.Source.WIDGET, IllegalStateException(secret))
        assertEquals("Виджет: не удалось обратиться к мониторингу (IllegalStateException)", message)
        assertFalse("Exception text may contain addresses or tokens", message.contains("token"))
    }

    @Test fun anonymousExceptionClassesStillProduceALabel() {
        val anonymous = object : RuntimeException() {}
        val message = LaunchFailures.message(LaunchFailures.Source.TILE, anonymous)
        assertEquals("Плитка: не удалось обратиться к мониторингу (Exception)", message)
    }
}
