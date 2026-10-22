package ru.tgwatch.wear

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Both surfaces use this frozen, cache-only frame, including in ambient mode. */
data class WatchPresentation(val shortText: String, val longText: String,
    val contentDescription: String, val stale: Boolean) {
    companion object {
        fun from(snapshot: WatchSnapshot?, nowElapsed: Long, nowWallMs: Long,
            russian: Boolean = Locale.getDefault().language == "ru"): WatchPresentation {
            if (snapshot == null) return missing(russian)
            // Runtime invalidates elapsed on boot change before exposing the persisted cache.
            val old = snapshot.stale(nowElapsed, snapshot.bootSession) || nowWallMs < snapshot.checkedAtMs
            val status = statusLabel(snapshot.status, russian)
            val time = formatTime(snapshot.checkedAtMs)
            val short = shortLabel(old, snapshot.status, status, russian)
            val long = longLabel(old, status, time, russian)
            val description = description(long, status, russian)
            return WatchPresentation(short, long, description, old)
        }

        private fun missing(russian: Boolean): WatchPresentation = WatchPresentation(
            "—",
            if (russian) "Нет результата" else "No result",
            if (russian) "На часах ещё нет измерений. Нажмите для проверки."
            else "No measurement on this watch. Tap to check.",
            true,
        )

        private fun statusLabel(status: String, russian: Boolean): String = when (status) {
            "OK" -> if (russian) "Доступен" else "Available"
            "PARTIAL" -> if (russian) "Частично" else "Partial"
            "NO_NETWORK" -> if (russian) "Нет сети" else "Offline"
            else -> if (russian) "Не подтверждено" else "Unconfirmed"
        }

        private fun formatTime(checkedAtMs: Long): String =
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(checkedAtMs))

        private fun shortLabel(old: Boolean, rawStatus: String, status: String, russian: Boolean): String = when {
            old -> if (russian) "Устарел" else "Old"
            rawStatus == "OK" -> "OK"
            else -> status
        }

        private fun longLabel(old: Boolean, status: String, time: String, russian: Boolean): String = when {
            old -> if (russian) "Результат устарел · $time" else "Last measured · $time"
            else -> "$status · $time"
        }

        private fun description(long: String, status: String, russian: Boolean): String = if (russian) {
            "Измерено на часах. $long. Последний результат: $status. Нажмите для проверки."
        } else {
            "Measured on this watch. $long. Last measured status: $status. Tap to check."
        }
    }
}
