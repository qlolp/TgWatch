package ru.tgwatch

import android.content.Context

fun timeStr(ctx: Context, millis: Long): String = AndroidText.resolve(ctx, timeText(millis))
fun durationStr(ctx: Context, millis: Long): String = AndroidText.resolve(ctx, DurationParts.from(millis).text())
fun agoStr(ctx: Context, millis: Long, now: Long = System.currentTimeMillis()): String =
    AndroidText.resolve(ctx, ageText(millis, now))
fun recoveryDurationStr(ctx: Context, millis: Long): String =
    AndroidText.resolve(ctx, DurationParts.from(millis).text(true))
fun formatPercent(ctx: Context, percent: Double): String = AndroidText.percent(ctx, percent)
