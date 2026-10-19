package ru.tgwatch

import android.content.Context
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Resolves only at an Android boundary. No Context or translated value is kept globally. */
object AndroidText {
    private const val ALMOST_FULL = 99.95
    private const val BELOW_FULL = 99.9
    private const val FULL = 100.0

    fun resolve(ctx: Context, value: TextValue): String = when (value.key) {
        TextKey.LINES, TextKey.BLOCKS -> value.args.joinToString(
            if (value.key == TextKey.LINES) "\n" else "\n\n") { argument(ctx, it).toString() }
        TextKey.PERCENT -> percent(ctx, (value.args.single() as TextArgument.Decimal).value)
        TextKey.DAILY_CHECKS -> quantity(ctx, value.key,
            (value.args.single() as TextArgument.Number).value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), value.args)
        TextKey.LAST_SUCCESS, TextKey.LAST_SUCCESS_CLOCK_CHANGED -> {
            val at = (value.args.first() as TextArgument.Number).value
            val zone = (value.args[1] as TextArgument.Text).value
            val date = SimpleDateFormat("dd.MM HH:mm:ss", locale(ctx)).apply { timeZone = TimeZone.getTimeZone(zone) }
            ctx.getString(textResources.getValue(value.key), date.format(Date(at)))
        }
        else -> ResourceTextFormat.string(ctx.resources, textResources.getValue(value.key),
            value.args.map { argument(ctx, it) }.toTypedArray())
    }

    fun quantity(ctx: Context, key: TextKey, count: Int,
        args: List<TextArgument> = listOf(TextArgument.Number(count.toLong()))): String {
        require(key == TextKey.DAILY_CHECKS)
        return ResourceTextFormat.quantity(ctx.resources, R.plurals.text_daily_checks, count,
            args.map { argument(ctx, it) }.toTypedArray())
    }

    fun percent(ctx: Context, value: Double): String {
        val measured = if (value >= ALMOST_FULL && value < FULL) BELOW_FULL else value
        val number = NumberFormat.getNumberInstance(locale(ctx)).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = 1
            roundingMode = RoundingMode.HALF_UP
        }.format(BigDecimal.valueOf(measured))
        return ctx.getString(R.string.text_percent_value, number)
    }
    private fun argument(ctx: Context, value: TextArgument): Any = when (value) {
        is TextArgument.Number -> value.value
        is TextArgument.Decimal -> percent(ctx, value.value)
        is TextArgument.Text -> value.value
        is TextArgument.Duration -> resolve(ctx, value.parts.text(value.precise))
        is TextArgument.Value -> resolve(ctx, value.value)
    }
    private fun locale(ctx: Context): Locale = Locale.forLanguageTag(ctx.getString(R.string.lang))
}
