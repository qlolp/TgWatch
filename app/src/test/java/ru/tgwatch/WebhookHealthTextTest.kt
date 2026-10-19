package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class WebhookHealthTextTest {
    @Test fun usesMonotonicAgesAndShowsTimeoutWhilePhysicalRequestRemains() {
        val text = WebhookHealthText.format(
            WebhookHealth(10_000, 5_000, WebhookOutcome.TIMED_OUT, true, transportBlocked = true), 20_000)
        assertTrue(text.allArguments().containsAll(listOf(TextArgument.Number(10), TextArgument.Number(15))))
        assertTrue(text.allKeys().contains(TextKey.COPY_WEBHOOK_HEALTH_TEXT_TIMED_OUT))
        assertTrue(text.allKeys().contains(TextKey.COPY_WEBHOOK_HEALTH_TEXT_DELIVERY_BLOCKED_THE_PREVIOUS))
        assertTrue(text.allKeys().contains(TextKey.COPY_WEBHOOK_HEALTH_TEXT_YES))
    }
    @Test fun clearedHealthIsProcessLocalAndUnmeasured() {
        val text = WebhookHealthText.format(WebhookHealth(), 500_000)
        assertTrue(text.allKeys().contains(TextKey.COPY_WEBHOOK_HEALTH_TEXT_NO_ATTEMPTS))
        assertEquals(2, text.allKeys().count { it == TextKey.COPY_WEBHOOK_HEALTH_TEXT_NONE })
        assertFalse(text.allArguments().any { it is TextArgument.Number })
    }
}
