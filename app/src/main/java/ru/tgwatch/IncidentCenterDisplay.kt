package ru.tgwatch

import android.content.Context

internal data class IncidentCenterKey(
    val ids: List<TargetId>,
    val versions: Map<TargetId, Long>,
    val endpointRevisions: Map<TargetId, Long>,
    val generation: String?,
    val regionalGeneration: Long,
    val minute: Long,
    val days: Int,
)

internal data class IncidentCenterCard(val text: String, val targetId: TargetId?)
internal data class IncidentCenterDisplay(
    val key: IncidentCenterKey,
    val at: Long,
    val cards: List<IncidentCenterCard>,
    val coverage: String,
)

internal object IncidentCenterLoader {
    private const val MINUTE_MS = 60_000L
    private const val DAY = 1
    private const val WEEK = 7

    fun key(ctx: Context, days: Int, now: Long): IncidentCenterKey {
        val ids = TargetId.entries.filter { TargetPreferences.definition(ctx, it).enabled }
        return IncidentCenterKey(ids, ids.associateWith(TargetHistoryPresentation::version),
            ids.associateWith { TargetPreferences.endpointRevision(ctx, it) },
            TargetPersistence.active?.generationId, RegionalRepository.generation(ctx), now / MINUTE_MS,
            days.coerceIn(DAY, WEEK))
    }

    fun load(ctx: Context, key: IncidentCenterKey): IncidentCenterDisplay {
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val from = (now - key.days * CoverageStats.DAY_MS).coerceAtLeast(0L)
        val stats = key.ids.associateWith { TargetHistories.stats(ctx, it, key.days, now).valueOrThrow() }
        val episodes = key.ids.associateWith { id ->
            OutageEpisodes.derive(TargetHistories.rows(ctx, id, now), from, now)
        }
        val snapshot = IncidentCenter.build(episodes, RegionalRepository.snapshot(ctx).rows, stats, from, now)
        val cards = snapshot.events.map { event -> IncidentCenterCard(describe(ctx, event), event.targetId) }
        return IncidentCenterDisplay(key, now, cards, ObservationCoverageText.all(ctx, snapshot.coverage))
    }

    private fun describe(ctx: Context, event: IncidentCenter.Event): String {
        val at = AndroidText.resolve(ctx, absoluteTimeText(event.at, java.util.TimeZone.getDefault()))
        return when (event.kind) {
            IncidentCenter.Kind.OUTAGE -> AndroidText.resolve(ctx, IncidentPresentation.describe(
                checkNotNull(event.episode), targetId = checkNotNull(event.targetId)))
            IncidentCenter.Kind.NETWORK -> ctx.getString(R.string.incident_center_network,
                route(ctx, event.route ?: RegionalRoute.UNKNOWN), at)
            IncidentCenter.Kind.COUNTRY -> {
                val country = checkNotNull(event.country)
                ctx.getString(R.string.incident_center_country,
                    EgressCountry.label(country).ifEmpty { country }, at)
            }
            IncidentCenter.Kind.GEO_BLOCK -> ctx.getString(R.string.incident_center_geo,
                checkNotNull(event.service).title, at)
        }
    }

    private fun route(ctx: Context, value: RegionalRoute): String = ctx.getString(when (value) {
        RegionalRoute.WIFI -> R.string.regional_route_wifi
        RegionalRoute.CELLULAR -> R.string.regional_route_cellular
        RegionalRoute.VPN -> R.string.regional_route_vpn
        RegionalRoute.ETHERNET -> R.string.regional_route_ethernet
        else -> R.string.regional_route_other
    })
}
