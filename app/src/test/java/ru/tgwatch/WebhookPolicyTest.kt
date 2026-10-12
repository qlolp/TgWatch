package ru.tgwatch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebhookPolicyTest {
    @Before @After fun russianDefault() { Lang.ru = true }

    @Test fun urlValidationAllowsHttpsTopicsAndQueriesButNoCredentialsOrFragments() {
        for (good in listOf("https://ntfy.sh/my-topic_42", "https://ntfy.example.org:8443/alerts?title=TG&priority=4",
            "https://hooks.example.org/services/T000/B000/XXXX")) {
            assertEquals(good, WebhookPolicy.normalizeUrl(good))
        }
        assertEquals("https://ntfy.sh/t", WebhookPolicy.normalizeUrl("\thttps://ntfy.sh/t "))
        for (bad in listOf(null, "", "http://ntfy.sh/topic", "ntfy.sh/topic", "https://user:pw@ntfy.sh/topic",
            "https://ntfy.sh/topic#frag", "https://ntfy.sh/to pic", "https://ntfy.sh/тема", "file:///etc/hosts",
            "https://ntfy.sh/" + "a".repeat(WebhookPolicy.MAX_URL_LENGTH))) {
            assertNull("Accepted $bad", WebhookPolicy.normalizeUrl(bad))
        }
        val longest = "https://ntfy.sh/" + "a".repeat(WebhookPolicy.MAX_URL_LENGTH - 16)
        assertEquals(longest, WebhookPolicy.normalizeUrl(longest))
    }

    @Test fun displayRevealsOnlyTheHost() {
        assertEquals("ntfy.sh", HttpsUrlPolicy.hostLabel("https://ntfy.sh/secret-topic?auth=x"))
        assertEquals("", HttpsUrlPolicy.hostLabel("not a url"))
    }

    @Test fun payloadIsOneShortStatusLineInBothLanguages() {
        assertEquals("TG Монитор: Telegram недоступен", WebhookPolicy.payload("TG_DOWN"))
        assertEquals("TG Монитор: Telegram доступен", WebhookPolicy.payload("OK"))
        Lang.ru = false
        assertEquals("TG Monitor: Telegram is unreachable", WebhookPolicy.payload("TG_DOWN"))
        assertEquals("TG Monitor: Telegram is partially reachable", WebhookPolicy.payload("PARTIAL"))
        assertEquals("TG Monitor: no network", WebhookPolicy.payload("NO_NETWORK"))
        assertEquals("TG Monitor: test message", WebhookPolicy.testPayload())
        assertNull(WebhookPolicy.payload("UNKNOWN"))
        assertNull(WebhookPolicy.payload("garbage"))
        for (status in listOf("OK", "PARTIAL", "TG_DOWN", "NO_NETWORK")) for (ru in listOf(true, false)) {
            Lang.ru = ru
            val text = WebhookPolicy.payload(status)!!
            assertTrue(text, text.length <= 64 && '\n' !in text && "http" !in text && text.none { it.isDigit() })
        }
    }

    @Test fun userAgentUsesSanitizedAppVersion() {
        assertEquals("TgWatch/1.13.0 (Android)", WebhookPolicy.userAgent("1.13.0"))
        assertEquals("TgWatch/1.13.0-debug+2 (Android)", WebhookPolicy.userAgent("1.13.0-debug+2"))
        for (bad in listOf(null, "", "1.13\r\nX-Evil: 1", "1.13 (x)", "версия", "9".repeat(33))) {
            assertEquals("TgWatch/unknown (Android)", WebhookPolicy.userAgent(bad))
        }
        assertTrue(WebhookPolicy.delivered(200) && WebhookPolicy.delivered(204))
        assertFalse(WebhookPolicy.delivered(-1) || WebhookPolicy.delivered(302) || WebhookPolicy.delivered(429))
    }

    @Test fun healthyStartIsSilentAndEachRealChangeIsSentOnce() {
        val gate = WebhookGate()
        assertFalse("Startup OK is a baseline, not news", gate.begin("OK", 1_000))
        assertFalse(gate.begin("OK", 2_000))
        assertTrue("First change is sent immediately", gate.begin("TG_DOWN", 3_000))
        gate.finished("TG_DOWN", true)
        assertFalse("Same status is not repeated", gate.begin("TG_DOWN", 200_000))
        assertFalse("UNKNOWN is never sent", gate.begin("UNKNOWN", 200_001))
        assertFalse("UNKNOWN does not reset the last delivered status", gate.begin("TG_DOWN", 200_002))
        assertTrue(gate.begin("OK", 200_003))
        gate.finished("OK", true)
    }

    @Test fun firstNonOkStatusAfterStartIsSent() {
        val gate = WebhookGate()
        assertTrue(gate.begin("TG_DOWN", 0))
        gate.finished("TG_DOWN", true)
        assertTrue("A first OK after a reported outage is a change", gate.begin("OK", 1))
    }

    @Test fun perStatusRateLimitDelaysFlappingButConvergesToCurrentStatus() {
        val gate = WebhookGate(minGapMs = 60_000)
        gate.begin("OK", 0)
        assertTrue(gate.begin("TG_DOWN", 1_000)); gate.finished("TG_DOWN", true)
        assertTrue("First post of a different status is immediate", gate.begin("OK", 2_000)); gate.finished("OK", true)
        assertFalse("TG_DOWN again within 60 s is held back", gate.begin("TG_DOWN", 30_000))
        assertFalse(gate.begin("TG_DOWN", 60_999))
        assertTrue("A later check reports the held-back status", gate.begin("TG_DOWN", 61_000))
        gate.finished("TG_DOWN", true)
        assertFalse(gate.begin("OK", 61_500))
        assertTrue(gate.begin("OK", 62_000))
    }

    @Test fun atMostOneRequestInFlightAndFailuresRetryAfterTheGap() {
        val gate = WebhookGate(minGapMs = 60_000)
        gate.begin("OK", 0)
        assertTrue(gate.begin("TG_DOWN", 1_000))
        assertFalse("In flight", gate.begin("NO_NETWORK", 1_500))
        assertFalse("Tests wait for the in-flight request", gate.beginTest(1_600))
        gate.finished("TG_DOWN", false)
        assertFalse("Failed post is retried only after the gap", gate.begin("TG_DOWN", 30_000))
        assertTrue(gate.begin("TG_DOWN", 61_000))
        gate.finished("TG_DOWN", true)
        assertFalse(gate.begin("TG_DOWN", 200_000))
    }

    @Test fun failedDeliveryRestoresThePreviousDeliveredStatus() {
        val gate = WebhookGate()
        gate.begin("OK", 0)
        assertTrue(gate.begin("NO_NETWORK", 10))
        gate.finished("NO_NETWORK", false)
        assertFalse("Remote side still believes OK; nothing new to say", gate.begin("OK", 20))
    }

    @Test fun testMessagesAreRateLimitedAndDoNotChangeStatusHistory() {
        val gate = WebhookGate(testGapMs = 10_000)
        assertTrue(gate.beginTest(0))
        assertFalse(gate.begin("TG_DOWN", 1))
        gate.finishedTest()
        assertFalse("Test gap", gate.beginTest(5_000))
        assertTrue(gate.begin("TG_DOWN", 6_000))
        gate.finished("TG_DOWN", true)
        assertTrue(gate.beginTest(10_000))
        gate.finishedTest()
    }

    @Test fun resetForgetsHistoryAfterSettingsChange() {
        val gate = WebhookGate()
        gate.begin("OK", 0)
        assertTrue(gate.begin("TG_DOWN", 1)); gate.finished("TG_DOWN", true)
        gate.reset()
        assertTrue("New address hears about the current outage", gate.begin("TG_DOWN", 2))
        gate.finished("TG_DOWN", true)
        gate.reset()
        assertFalse(gate.begin("OK", 3))
    }
}
