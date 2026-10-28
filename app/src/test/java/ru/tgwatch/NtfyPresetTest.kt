package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtfyPresetTest {
    @Test fun composesOrdinaryWebhookAddress() {
        assertEquals("https://ntfy.sh/tg-watch_1", NtfyPreset.url("", "tg-watch_1", ""))
        assertEquals("https://ntfy.example.org/t", NtfyPreset.url("https://ntfy.example.org/", " t ", null))
        assertEquals("https://ntfy.sh/t?auth=QmVhcmVyIHRrX2FiYzEyMw",
            NtfyPreset.url(null, "t", "tk_abc123"))
    }

    @Test fun rejectsUnsafeParts() {
        assertNull(NtfyPreset.url("http://ntfy.sh", "t", ""))
        assertNull(NtfyPreset.url("https://ntfy.sh/path", "t", ""))
        assertNull(NtfyPreset.url("https://user@ntfy.sh", "t", ""))
        assertNull(NtfyPreset.url("", "", ""))
        assertNull(NtfyPreset.url("", "a/b", ""))
        assertNull(NtfyPreset.url("", "t".repeat(65), ""))
        assertNull(NtfyPreset.url("", "t", "not-a-token"))
        assertNull(NtfyPreset.url("", "t", "tk_" + "a".repeat(NtfyPreset.MAX_TOKEN_LENGTH)))
    }
}
