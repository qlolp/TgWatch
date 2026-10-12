package ru.tgwatch

/** An observed failure episode. Uncertain intervals never contribute to down or partial duration. */
data class OutageEpisode(val startedAt: Long, val endedAt: Long, val recovered: Boolean,
    val startCensored: Boolean, val downMs: Long, val partialMs: Long, val offlineMs: Long,
    val unknownMs: Long, val clockEpoch: Long)

internal object OutageEpisodes {
    private const val MAX_EPISODES = 100

    fun derive(samples: List<Observation>, from: Long, until: Long): List<OutageEpisode> =
        derivePrepared(Timeline.currentChecks(samples).sortedBy { it.at }, from, until)

    fun derivePrepared(checks: List<Observation>, from: Long, until: Long): List<OutageEpisode> {
        val result = ArrayDeque<OutageEpisode>()
        var active: OutageEpisode? = null
        var previous = "UNKNOWN"
        for (segment in Timeline.segments(checks, from, until)) {
            val length = segment.until - segment.at
            if (active == null && (segment.kind == "TG_DOWN" || segment.kind == "PARTIAL")) {
                active = OutageEpisode(segment.at, segment.at, false, previous != "OK",
                    0, 0, 0, 0, segment.clockEpoch)
            }
            val episode = active
            if (episode != null && segment.kind == "OK") {
                result.addLast(episode.copy(endedAt = segment.at, recovered = true))
                if (result.size > MAX_EPISODES) result.removeFirst()
                active = null
            } else if (episode != null) active = extend(episode, segment, length)
            previous = segment.kind
        }
        active?.let { result.addLast(it) }
        return result.toList().takeLast(MAX_EPISODES).asReversed()
    }

    private fun extend(episode: OutageEpisode, segment: Observation, length: Long) =
        episode.copy(endedAt = segment.until,
            downMs = episode.downMs + if (segment.kind == "TG_DOWN") length else 0,
            partialMs = episode.partialMs + if (segment.kind == "PARTIAL") length else 0,
            offlineMs = episode.offlineMs + if (segment.kind == "NO_NETWORK") length else 0,
            unknownMs = episode.unknownMs + if (segment.kind == "UNKNOWN") length else 0)
}
