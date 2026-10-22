package ru.tgwatch

import android.content.Context

/** Builds allowlisted historical metadata only after the monitor/country lookup accepted its generation. */
internal object RegionalJournalObserver {
    fun record(ctx: Context, result: RegionalResult, journalGeneration: Long) {
        if (journalGeneration != RegionalRepository.generation(ctx)) return
        val connection = RegionalContext.current(ctx, result.networkHandle, result.configurationRevision)
        RegionalRepository.recordEntries(ctx, listOf(entry(result, connection)), includeContext = true,
            expectedGeneration = journalGeneration)
    }

    fun entry(result: RegionalResult, connection: RegionalConnection): RegionalJournalEntry {
        val countryAt = connection.countryAt?.takeIf { it <= result.checkedAt }
        return RegionalJournalEntry(RegionalEventKind.PUBLIC, result.checkedAt.coerceAtLeast(0),
            connection.token, connection.route, connection.country.takeIf { countryAt != null }, countryAt,
            result.service, result.status, result.regionConfirmed,
            result.httpCode?.takeIf(RegionalJournalCodec::validHttpCode), result.failure)
    }

    fun country(ctx: Context, country: EgressCountry.Snapshot, journalGeneration: Long) {
        if (!RegionalPreferences.anyEnabled(ctx) || journalGeneration != RegionalRepository.generation(ctx)) return
        val connection = RegionalContext.current(ctx, country.networkHandle, RegionalPreferences.revision(ctx))
        if (connection.country != country.iso) return
        RegionalRepository.recordEntries(ctx, listOf(RegionalJournalEntry(RegionalEventKind.COUNTRY,
            country.checkedAt, connection.token, connection.route, country.iso, country.checkedAt)),
            includeContext = true, expectedGeneration = journalGeneration)
    }
}
