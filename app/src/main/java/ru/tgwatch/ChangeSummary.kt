package ru.tgwatch

/**
 * Pure “what changed” narrative for the last day, plus egress/network ↔ outage coincidence.
 * Correlation is temporal proximity only — never causation.
 */
object ChangeSummary {
    const val WINDOW_MS = 24 * 60 * 60_000L
    /** Outage onset within this window of a COUNTRY or NETWORK journal event counts as a coincidence. */
    const val CORRELATE_MS = 15 * 60_000L

    data class TargetFact(
        val id: TargetId,
        val status: TargetStatus,
        val outageCount: Int,
        val ongoing: Boolean = false,
        val lastOutageMs: Long = 0L,
    )

    data class CountryChange(val from: String?, val to: String, val at: Long) {
        init {
            require(to.isNotEmpty() && EgressCountry.isValid(to))
            require(from == null || EgressCountry.isValid(from))
            require(at >= 0)
        }
    }

    data class NetworkChange(val route: RegionalRoute, val at: Long) {
        init { require(at >= 0) }
    }

    data class GeoBlockFact(val service: RegionalService, val count: Int) {
        init { require(count > 0) }
    }

    data class Correlation(val outages: Int, val matched: Int) {
        init { require(outages >= 0 && matched in 0..outages) }
        val notable: Boolean get() = outages > 0 && matched > 0
    }

    data class Input(
        val now: Long,
        val targets: List<TargetFact>,
        val countryChanges: List<CountryChange>,
        val networkChanges: List<NetworkChange>,
        val geoBlocks: List<GeoBlockFact>,
        val outages: List<OutageEpisode>,
    ) {
        init { require(now >= 0) }
    }

    data class Text(val title: TextValue, val body: TextValue)

    fun text(input: Input): Text? {
        if (quiet(input)) return null
        val lines = buildList {
            input.targets.forEach { add(targetLine(it)) }
            input.geoBlocks.sortedBy { it.service.wire }.forEach { add(geoLine(it)) }
            input.countryChanges.lastOrNull()?.let { add(countryLine(it, input.now)) }
            val vpn = input.networkChanges.lastOrNull { it.route == RegionalRoute.VPN }
            if (vpn != null) add(textOf(TextKey.CHANGE_NETWORK_VPN, TextArgument.Value(ageText(vpn.at, input.now))))
            val correlation = correlate(input.outages, changeMoments(input))
            if (correlation.notable) {
                add(textOf(TextKey.CHANGE_CORRELATION,
                    TextArgument.Number(correlation.matched.toLong()),
                    TextArgument.Number(correlation.outages.toLong())))
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.let {
            Text(TextValue(TextKey.CHANGE_TITLE), textLines(it))
        }
    }

    private fun quiet(input: Input): Boolean {
        val correlation = correlate(input.outages, changeMoments(input))
        return input.targets.all { it.status == TargetStatus.OK && it.outageCount == 0 } &&
            input.countryChanges.isEmpty() && input.geoBlocks.isEmpty() &&
            input.networkChanges.none { it.route == RegionalRoute.VPN } &&
            !correlation.notable
    }

    fun correlate(outages: List<OutageEpisode>, changeAts: List<Long>,
        windowMs: Long = CORRELATE_MS): Correlation {
        require(windowMs >= 0)
        val moments = changeAts.filter { it >= 0 }.distinct().sorted()
        if (outages.isEmpty()) return Correlation(0, 0)
        val matched = outages.count { episode ->
            moments.any { at ->
                val delta = episode.startedAt - at
                delta in -windowMs..windowMs
            }
        }
        return Correlation(outages.size, matched)
    }

    /** Distinct COUNTRY ISO transitions inside [from, until], using the last prior COUNTRY as baseline. */
    fun countryChanges(rows: List<RegionalJournalEntry>, from: Long, until: Long): List<CountryChange> {
        require(from >= 0 && until >= from)
        val countries = rows.asSequence()
            .filter { it.kind == RegionalEventKind.COUNTRY && it.country != null && EgressCountry.isValid(it.country) }
            .sortedBy { it.at }
            .toList()
        var previous = countries.lastOrNull { it.at < from }?.country
        return countries.filter { it.at in from..until }.mapNotNull { row ->
            val to = checkNotNull(row.country)
            if (to == previous) null else CountryChange(previous, to, row.at).also { previous = to }
        }
    }

    /** NETWORK rows in the window; consecutive identical route+scope pairs are already deduped by the journal. */
    fun networkChanges(rows: List<RegionalJournalEntry>, from: Long, until: Long): List<NetworkChange> {
        require(from >= 0 && until >= from)
        return rows.asSequence()
            .filter { it.kind == RegionalEventKind.NETWORK && it.at in from..until }
            .sortedBy { it.at }
            .map { NetworkChange(it.route, it.at) }
            .toList()
    }

    fun geoBlocks(rows: List<RegionalJournalEntry>, from: Long, until: Long): List<GeoBlockFact> {
        require(from >= 0 && until >= from)
        return rows.asSequence()
            .filter {
                it.kind == RegionalEventKind.PUBLIC && it.status == RegionalStatus.REGION_RESTRICTED &&
                    it.service != null && it.at in from..until
            }
            .groupingBy { checkNotNull(it.service) }
            .eachCount()
            .map { (service, count) -> GeoBlockFact(service, count) }
            .sortedBy { it.service.wire }
    }

    private fun changeMoments(input: Input): List<Long> =
        input.countryChanges.map { it.at } + input.networkChanges.map { it.at }

    private fun targetLine(fact: TargetFact): TextValue {
        val name = TextArgument.Value(TargetNames.text(fact.id))
        return when {
            fact.status == TargetStatus.OK && fact.outageCount == 0 ->
                textOf(TextKey.CHANGE_TARGET_OK, name)
            fact.ongoing -> textOf(TextKey.CHANGE_TARGET_ONGOING, name,
                TextArgument.Duration(DurationParts.from(fact.lastOutageMs), precise = true))
            fact.outageCount > 0 -> textOf(TextKey.CHANGE_TARGET_OUTAGES, name,
                TextArgument.Number(fact.outageCount.toLong()))
            fact.status == TargetStatus.DOWN -> textOf(TextKey.CHANGE_TARGET_DOWN, name)
            fact.status == TargetStatus.PARTIAL -> textOf(TextKey.CHANGE_TARGET_PARTIAL, name)
            fact.status == TargetStatus.NO_NETWORK -> textOf(TextKey.CHANGE_TARGET_OFFLINE, name)
            else -> textOf(TextKey.CHANGE_TARGET_UNKNOWN, name)
        }
    }

    private fun geoLine(fact: GeoBlockFact): TextValue =
        textOf(TextKey.CHANGE_GEO_BLOCK, TextArgument.Text(fact.service.title),
            TextArgument.Number(fact.count.toLong()))

    private fun countryLine(change: CountryChange, now: Long): TextValue {
        val age = TextArgument.Value(ageText(change.at, now))
        val to = TextArgument.Text(EgressCountry.label(change.to).ifEmpty { change.to })
        return if (change.from == null) textOf(TextKey.CHANGE_EGRESS_SET, to, age)
        else textOf(TextKey.CHANGE_EGRESS_SWITCH,
            TextArgument.Text(EgressCountry.label(change.from).ifEmpty { change.from }), to, age)
    }
}
