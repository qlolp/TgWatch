package ru.tgwatch

import android.content.Context
import java.text.DateFormat
import java.util.Date

/** Android resource boundary shared by history, comparisons and explanations. */
internal object RegionalPresentation {
    fun status(ctx: Context, status: RegionalStatus, confirmed: Boolean = false): String = ctx.getString(
        if (confirmed) R.string.regional_confirmed else when (status) {
            RegionalStatus.WEB_REACHABLE -> R.string.regional_web_reachable
            RegionalStatus.REGION_RESTRICTED -> R.string.regional_restricted
            RegionalStatus.AUTH_REQUIRED -> R.string.regional_auth
            RegionalStatus.CHALLENGE -> R.string.regional_challenge
            RegionalStatus.RATE_LIMITED -> R.string.regional_rate
            RegionalStatus.NETWORK_ERROR -> R.string.regional_network
            RegionalStatus.UNKNOWN -> R.string.regional_unverified
        })

    fun route(ctx: Context, route: RegionalRoute): String = ctx.getString(when (route) {
        RegionalRoute.WIFI -> R.string.regional_route_wifi
        RegionalRoute.CELLULAR -> R.string.regional_route_cellular
        RegionalRoute.VPN -> R.string.regional_route_vpn
        RegionalRoute.ETHERNET -> R.string.regional_route_ethernet
        RegionalRoute.OTHER -> R.string.regional_route_other
        RegionalRoute.UNKNOWN -> R.string.regional_unverified
        RegionalRoute.NONE -> R.string.country_no_network
    })

    fun time(ctx: Context, at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT,
        DateFormat.MEDIUM, ctx.resources.configuration.locales[0]).format(Date(at))

    fun event(ctx: Context, row: RegionalJournalEntry): String {
        val detail = when (row.kind) {
            RegionalEventKind.NETWORK -> ctx.getString(R.string.regional_history_connection)
            RegionalEventKind.COUNTRY -> ctx.getString(R.string.regional_history_country)
            RegionalEventKind.PUBLIC -> ctx.getString(R.string.regional_history_public, row.service!!.title,
                status(ctx, row.status!!, row.confirmed))
            RegionalEventKind.BROWSER, RegionalEventKind.FEATURE -> ctx.getString(R.string.regional_history_report,
                row.service!!.title, RegionalVerificationUi.source(ctx, if (row.kind == RegionalEventKind.BROWSER)
                    RegionalVerificationLayer.BROWSER else RegionalVerificationLayer.FEATURE),
                RegionalVerificationUi.outcome(ctx, row.verification!!))
        }
        val country = row.country?.let { ctx.getString(R.string.regional_history_country_value,
            EgressCountry.flag(it), it, time(ctx, row.countryAt!!)) } ?: ctx.getString(R.string.country_unverified)
        val code = row.httpCode?.let { ctx.getString(R.string.regional_history_code, it) }.orEmpty()
        return ctx.getString(R.string.regional_history_row, time(ctx, row.at), detail,
            route(ctx, row.route), country, code)
    }
}
