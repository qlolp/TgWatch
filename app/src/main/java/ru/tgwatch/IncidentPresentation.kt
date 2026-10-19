package ru.tgwatch

import java.util.TimeZone

/** Only aggregate episode fields enter an explicitly shared summary. */
internal object IncidentPresentation {
    fun describe(episode: OutageEpisode, zone: TimeZone = TimeZone.getDefault(),
        targetId: TargetId = TargetId.TELEGRAM): TextValue {
        val name = TargetNames.text(targetId)
        return textJoin(buildList {
            add(textOf(TextKey.COPY_INCIDENT_PRESENTATION_TG_MONITOR_INCIDENT))
            add(textOf(TextKey.COPY_INCIDENT_PRESENTATION_OBSERVED_ONSET) + absoluteTimeText(episode.startedAt,
                zone) + "\n")
            if (episode.startCensored) add(textOf(TextKey.COPY_INCIDENT_PRESENTATION_THE_FULL_ONSET_WAS))
            add((if (episode.recovered) textOf(TextKey.COPY_INCIDENT_PRESENTATION_OBSERVED_RECOVERY)
                else textOf(TextKey.COPY_INCIDENT_PRESENTATION_RECOVERY_WAS_NOT_OBSERVED)) + absoluteTimeText(
                    episode.endedAt, zone) + "\n")
            add(textOf(TextKey.COPY_INCIDENT_PRESENTATION_OUTAGE, textArgument(name)) + DurationParts.from(
                episode.downMs).text(true))
            add(textOf(TextKey.COPY_INCIDENT_PRESENTATION_PARTIAL_AVAILABILITY) +
                DurationParts.from(episode.partialMs).text(true))
            add(textOf(TextKey.COPY_INCIDENT_PRESENTATION_NO_INTERNET) + DurationParts.from(episode.offlineMs).text(
                true))
            add(textOf(TextKey.COPY_INCIDENT_PRESENTATION_NO_DATA) + DurationParts.from(episode.unknownMs).text(true))
            add(textOf(TextKey.COPY_INCIDENT_PRESENTATION_INTERVALS_ARE_ESTIMATED_FROM))
        }, "")
    }
}
