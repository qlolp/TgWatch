package ru.tgwatch

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class IncidentPresentationTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val episode = OutageEpisode(0, 60_000, recovered = true, startCensored = false,
        downMs = 10_000, partialMs = 20_000, offlineMs = 5000, unknownMs = 25_000, clockEpoch = 0)

    @Test fun allCoverageKindsHaveSeparateLabelsAndDurations() {
        val text = IncidentPresentation.describe(episode, Locale.US, utc)
        for (duration in listOf(10_000L, 20_000L, 5000L, 25_000L)) {
            assertTrue(text, text.contains(recoveryDurationStr(duration)))
        }
        assertTrue(text, text.contains(Lang.t("Сбой Telegram:", "Telegram outage:")))
        assertTrue(text, text.contains(Lang.t("Частичная доступность:", "Partial availability:")))
        assertTrue(text, text.contains(Lang.t("Нет сети:", "No internet:")))
        assertTrue(text, text.contains(Lang.t("Нет данных:", "No data:")))
    }

    @Test fun unobservedRecoveryNeverClaimsThatOutageIsOngoing() {
        val text = IncidentPresentation.describe(episode.copy(recovered = false), Locale.US, utc)
        assertTrue(text, text.contains(Lang.t("Восстановление не наблюдалось", "Recovery was not observed")))
        assertFalse(text, text.contains(Lang.t("Текущий сбой", "Current outage")))
    }

    @Test fun clippedStartIsExplicitAndTimesIncludeTheirUtcOffset() {
        val text = IncidentPresentation.describe(episode.copy(startCensored = true), Locale.US, utc)
        assertTrue(text, text.contains(Lang.t("Начало полностью не наблюдалось", "The full onset was not observed")))
        assertTrue(text, text.contains("1970-01-01 00:00:00 Z"))
        assertTrue(text, text.contains("1970-01-01 00:01:00 Z"))
    }

    @Test fun completedEpisodeShowsObservedRecovery() {
        val text = IncidentPresentation.describe(episode, Locale.US, utc)
        assertTrue(text, text.contains(Lang.t("Наблюдаемое восстановление:", "Observed recovery:")))
        assertFalse(text, text.contains(Lang.t("Восстановление не наблюдалось", "Recovery was not observed")))
    }

    @Test fun shareTextContainsOnlyTimesAndAggregates() {
        val text = IncidentPresentation.describe(episode, Locale.US, utc)
        for (privateMarker in listOf("https://", "endpoint", "token", "nonce", "clockEpoch", "192.168.", "Wi-Fi")) {
            assertFalse(privateMarker, text.contains(privateMarker))
        }
        assertTrue(text, text.contains("1970-01-01"))
    }
}
