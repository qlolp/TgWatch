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
        val text = IncidentPresentation.describe(episode, utc)
        for (duration in listOf(10_000L, 20_000L, 5000L, 25_000L)) {
            assertTrue(text.args.isNotEmpty())
            assertTrue(text.allArguments().any { it is TextArgument.Value && it.value == DurationParts.from(duration).text(true) })
        }
        assertTrue(text.toString(), text.allKeys().contains(TextKey.COPY_INCIDENT_PRESENTATION_OUTAGE))
        assertTrue(text.toString(), text.allKeys().contains(TextKey.COPY_INCIDENT_PRESENTATION_PARTIAL_AVAILABILITY))
        assertTrue(text.toString(), text.allKeys().contains(TextKey.COPY_INCIDENT_PRESENTATION_NO_INTERNET))
        assertTrue(text.toString(), text.allKeys().contains(TextKey.COPY_INCIDENT_PRESENTATION_NO_DATA))
    }

    @Test fun unobservedRecoveryNeverClaimsThatOutageIsOngoing() {
        val text = IncidentPresentation.describe(episode.copy(recovered = false), utc)
        assertTrue(text.toString(), text.allKeys().contains(TextKey.COPY_INCIDENT_PRESENTATION_RECOVERY_WAS_NOT_OBSERVED))
        assertFalse(text.toString(), text.allKeys().contains(TextKey.DAILY_ONGOING))
    }

    @Test fun clippedStartIsExplicitAndTimesIncludeTheirUtcOffset() {
        val text = IncidentPresentation.describe(episode.copy(startCensored = true), utc)
        assertTrue(text.toString(), text.allKeys().contains(TextKey.COPY_INCIDENT_PRESENTATION_THE_FULL_ONSET_WAS))
        assertTrue(text.toString(), text.allArguments().contains(TextArgument.Number(0)))
        assertTrue(text.toString(), text.allArguments().contains(TextArgument.Number(60_000)))
    }

    @Test fun completedEpisodeShowsObservedRecovery() {
        val text = IncidentPresentation.describe(episode, utc)
        assertTrue(text.toString(), text.allKeys().contains(TextKey.COPY_INCIDENT_PRESENTATION_OBSERVED_RECOVERY))
        assertFalse(text.toString(), text.allKeys().contains(TextKey.COPY_INCIDENT_PRESENTATION_RECOVERY_WAS_NOT_OBSERVED))
    }

    @Test fun shareTextContainsOnlyTimesAndAggregates() {
        val text = IncidentPresentation.describe(episode, utc)
        for (privateMarker in listOf("https://", "endpoint", "token", "nonce", "clockEpoch", "192.168.", "Wi-Fi")) {
            assertFalse(privateMarker, text.allArguments().any { it is TextArgument.Text && privateMarker in it.value })
        }
        assertTrue(text.toString(), text.allArguments().contains(TextArgument.Text("UTC")))
    }
}
