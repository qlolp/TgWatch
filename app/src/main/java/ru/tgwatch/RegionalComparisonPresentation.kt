package ru.tgwatch

import android.content.Context

internal data class RegionalComparisonViewKey(val saved: RegionalComparisonHistory,
    val a: RegionalComparisonSnapshot?, val latest: RegionalComparisonPair?)

internal object RegionalComparisonPresentation {
    fun render(ctx: Context, key: RegionalComparisonViewKey): String = buildString {
        key.a?.let { append(side(ctx, it)).append("\n\n") }
        key.latest?.let { append(pair(ctx, it)).append("\n\n") }
        append(ctx.getString(R.string.regional_compare_history)).append("\n")
        when {
            key.saved.error -> append(ctx.getString(R.string.regional_compare_save_error)).append("\n")
            key.saved.loading -> append(ctx.getString(R.string.regional_history_loading)).append("\n")
        }
        key.saved.pairs.asReversed().forEach { append(pair(ctx, it)).append("\n\n") }
    }

    private fun pair(ctx: Context, pair: RegionalComparisonPair): String = ctx.getString(
        if (pair.sameConnection) R.string.regional_compare_same else R.string.regional_compare_different) +
        "\n" + side(ctx, pair.a) + "\n\n" + side(ctx, pair.b)

    private fun side(ctx: Context, side: RegionalComparisonSnapshot): String {
        val connection = side.connection
        val country = connection.country?.let { ctx.getString(R.string.regional_history_country_value,
            EgressCountry.flag(it), it, RegionalPresentation.time(ctx, connection.countryAt!!)) }
            ?: ctx.getString(R.string.country_unverified)
        val header = ctx.getString(R.string.regional_compare_side, side.label, RegionalPresentation.time(ctx, side.at),
            RegionalPresentation.route(ctx, connection.route), country,
            ctx.getString(if (side.complete) R.string.regional_compare_complete
                else R.string.regional_compare_incomplete))
        return header + "\n" + ctx.getString(R.string.regional_source_public) + "\n" +
            side.readings.entries.sortedBy { it.key.ordinal }.joinToString("\n") { (service, row) ->
                val result = row?.let { RegionalPresentation.status(ctx, it.status!!, it.confirmed) }
                    ?: ctx.getString(R.string.regional_compare_unmeasured)
                ctx.getString(R.string.regional_compare_reading, service.title, result)
            }
    }
}
