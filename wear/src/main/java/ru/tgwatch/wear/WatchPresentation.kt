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
            if (snapshot == null) return WatchPresentation("—", if (russian) "Нет результата" else "No result",
                if (russian) "На часах ещё нет измерений. Нажмите для проверки." else
                    "No measurement on this watch. Tap to check.", true)
            // Runtime invalidates elapsed on boot change before exposing the persisted cache.
            val old = snapshot.stale(nowElapsed, snapshot.bootSession) || nowWallMs < snapshot.checkedAtMs
            val status = when (snapshot.status) {
                "OK" -> if (russian) "Доступен" else "Available"
                "PARTIAL" -> if (russian) "Частично" else "Partial"
                "NO_NETWORK" -> if (russian) "Нет сети" else "Offline"
                else -> if (russian) "Не подтверждено" else "Unconfirmed"
            }
            val time = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(snapshot.checkedAtMs))
            val short = if (old) { if (russian) "Устарел" else "Old" }
                else if (snapshot.status == "OK") "OK" else status
            val long = if (old) { if (russian) "Результат устарел · $time" else "Last measured · $time" }
                else "$status · $time"
            val description = if (russian) "Измерено на часах. $long. Последний результат: $status. Нажмите для проверки."
                else "Measured on this watch. $long. Last measured status: $status. Tap to check."
            return WatchPresentation(short, long, description, old)
        }
    }
}
