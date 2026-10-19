package ru.tgwatch

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId

data class NetCounts(val ok: Int = 0, val partial: Int = 0, val down: Int = 0) {
    val total: Int get() = ok + partial + down
    val okPercent: Double get() = if (total == 0) -1.0 else ok * PERCENT / total

    fun plus(status: String): NetCounts = when (status) {
        "OK" -> copy(ok = ok + 1)
        "PARTIAL" -> copy(partial = partial + 1)
        "TG_DOWN" -> copy(down = down + 1)
        else -> this
    }

    operator fun plus(other: NetCounts) = NetCounts(ok + other.ok, partial + other.partial, down + other.down)

    private companion object { const val PERCENT = 100.0 }
}

/**
 * Per local day and network kind: how many Telegram checks were fully OK, partial or failed.
 * Pure and bounded to [KEEP_DAYS]; checks without a Telegram verdict or a known network are not counted.
 */
class NetworkTally(rows: Map<Pair<Long, NetworkKind>, NetCounts> = emptyMap()) {
    private val rows = rows.toMutableMap()
    val snapshot: Map<Pair<Long, NetworkKind>, NetCounts> get() = rows.toMap()

    fun record(day: Long, kind: NetworkKind, status: String): Boolean {
        if (kind == NetworkKind.UNKNOWN) return false
        val key = day to kind
        val current = rows[key] ?: NetCounts()
        val updated = current.plus(status)
        if (updated == current) return false
        rows[key] = updated
        rows.keys.removeAll { it.first <= day - KEEP_DAYS }
        return true
    }

    /** Totals for the last [days] local days, including [today]. */
    fun totals(today: Long, days: Int): Map<NetworkKind, NetCounts> {
        val from = today - days.coerceIn(1, KEEP_DAYS) + 1
        return rows.filterKeys { it.first in from..today }.entries
            .groupBy({ it.key.second }, { it.value })
            .mapValues { (_, counts) -> counts.reduce(NetCounts::plus) }
    }

    fun encode(): String = rows.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .joinToString("\n") { (key, c) -> "${key.first},${key.second.name},${c.ok},${c.partial},${c.down}" }

    companion object {
        const val KEEP_DAYS = 7
        private const val FIELDS = 5
        private const val MIN_CHECKS = 5
        private const val VPN_GOOD = 95.0
        private const val DIRECT_BAD = 80.0
        private const val INSIGHT_CHECKS = 20

        /** Damaged lines are skipped: these are statistics, not the history itself. */
        fun decode(text: String): NetworkTally = NetworkTally(text.lineSequence().mapNotNull(::row).toMap())

        private fun row(line: String): Pair<Pair<Long, NetworkKind>, NetCounts>? {
            val p = line.split(',')
            val kind = NetworkKind.entries.firstOrNull { it.name == p.getOrNull(1) && it != NetworkKind.UNKNOWN }
            val day = p.firstOrNull()?.toLongOrNull()
            val n = p.drop(2).mapNotNull { it.toIntOrNull()?.takeIf { v -> v >= 0 } }
            val valid = kind != null && day != null && p.size == FIELDS && n.size == FIELDS - 2
            return if (valid) (day!! to kind!!) to NetCounts(n[0], n[1], n[2]) else null
        }

        fun label(kind: NetworkKind): TextValue = when (kind) {
            NetworkKind.WIFI -> textLiteral("Wi-Fi")
            NetworkKind.CELLULAR -> textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_MOBILE)
            NetworkKind.VPN -> textLiteral("VPN")
            NetworkKind.ETHERNET -> textLiteral("Ethernet")
            NetworkKind.OTHER, NetworkKind.UNKNOWN -> textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_OTHER)
        }

        /** Lines for the statistics card; null when fewer than two networks have enough checks to compare. */
        fun describe(totals: Map<NetworkKind, NetCounts>): TextValue? {
            val shown = totals.filterValues { it.total >= MIN_CHECKS }.toSortedMap()
            if (shown.size < 2) return null
            val parts = textJoin(shown.map { (kind, c) ->
                label(kind) + " " + textOf(TextKey.PERCENT, TextArgument.Decimal(c.okPercent))
            }, " · ")
            val vpnGood = shown[NetworkKind.VPN]?.let { it.total >= INSIGHT_CHECKS && it.okPercent >= VPN_GOOD } == true
            val directBad = shown.filterKeys { it != NetworkKind.VPN }.values
                .any { it.total >= INSIGHT_CHECKS && it.okPercent < DIRECT_BAD }
            return textLines(listOfNotNull(textOf(TextKey.HISTORY_BY_NETWORK, TextArgument.Value(parts)),
                if (vpnGood && directBad) textOf(TextKey.HISTORY_VPN_HELPS) else null))
        }
    }
}

/** Device-protected store for [NetworkTally]; writes at most once a minute, it is only statistics. */
object NetworkStats {
    private const val FILE = "network-stats-v1.csv"
    private const val WRITE_GAP_MS = 60_000L
    private var tally: NetworkTally? = null
    private var lastWrite = 0L
    private var dirty = false

    private fun file(ctx: Context) = File(ctx.applicationContext.createDeviceProtectedStorageContext().filesDir, FILE)

    private fun load(ctx: Context): NetworkTally = tally ?: run {
        val text = try { file(ctx).takeIf { it.exists() }?.readText().orEmpty() } catch (_: Exception) { "" }
        NetworkTally.decode(text).also { tally = it }
    }

    fun day(at: Long): Long = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    @Synchronized fun record(ctx: Context, at: Long, kind: NetworkKind, status: String) {
        if (!load(ctx).record(day(at), kind, status)) return
        dirty = true
        if (at - lastWrite >= WRITE_GAP_MS || at < lastWrite) flush(ctx, at)
    }

    @Synchronized fun flush(ctx: Context, at: Long = System.currentTimeMillis()) {
        val current = tally ?: return
        if (!dirty) return
        try {
            val target = file(ctx)
            val temp = File(target.parentFile, "$FILE.tmp")
            temp.writeText(current.encode())
            if (temp.renameTo(target)) {
                dirty = false
                lastWrite = at
            }
        } catch (_: Exception) {
            // Statistics only: the next check retries the write.
        }
    }

    @Synchronized fun describe(ctx: Context, days: Int, now: Long = System.currentTimeMillis()): TextValue? =
        NetworkTally.describe(load(ctx).totals(day(now), days))

    /** A restored history starts with fresh network statistics. */
    @Synchronized fun clear(ctx: Context) {
        tally = NetworkTally()
        dirty = false
        try { file(ctx).delete() } catch (_: Exception) { /* nothing to clear */ }
    }
}
