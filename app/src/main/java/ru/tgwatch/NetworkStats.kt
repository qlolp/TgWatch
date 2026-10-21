package ru.tgwatch

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId

// Even seven days with three counters at this ceiling have an exact Long sum.
private const val NETWORK_RESULT_KINDS = 3
private const val MAX_NETWORK_COUNT = Long.MAX_VALUE / NETWORK_RESULT_KINDS / NetworkTally.KEEP_DAYS

data class NetCounts(val ok: Long = 0, val partial: Long = 0, val down: Long = 0) {
    init { require(listOf(ok, partial, down).all { it in 0..Long.MAX_VALUE / NETWORK_RESULT_KINDS }) }
    val total: Long get() = Math.addExact(Math.addExact(ok, partial), down)
    val okPercent: Double get() = if (total == 0L) -1.0 else ok * PERCENT / total

    fun plus(status: String): NetCounts = when (status) {
        "OK" -> copy(ok = minOf(ok + 1, MAX_NETWORK_COUNT))
        "PARTIAL" -> copy(partial = minOf(partial + 1, MAX_NETWORK_COUNT))
        "TG_DOWN" -> copy(down = minOf(down + 1, MAX_NETWORK_COUNT))
        else -> this
    }

    operator fun plus(other: NetCounts) = NetCounts(Math.addExact(ok, other.ok),
        Math.addExact(partial, other.partial), Math.addExact(down, other.down))

    private companion object { const val PERCENT = 100.0 }
}

/**
 * Per local day and network kind: how many Telegram checks were fully OK, partial or failed.
 * Pure and bounded to [KEEP_DAYS]; checks without a Telegram verdict or a known network are not counted.
 */
class NetworkTally private constructor(rows: Map<Pair<Long, NetworkKind>, NetCounts>) {
    constructor() : this(emptyMap())
    private val rows = rows.toMutableMap()
    val snapshot: Map<Pair<Long, NetworkKind>, NetCounts> get() = rows.toMap()

    fun record(day: Long, kind: NetworkKind, status: String): Boolean {
        if (day < 0 || kind == NetworkKind.UNKNOWN || status !in setOf("OK", "PARTIAL", "TG_DOWN")) return false
        val from = (day - KEEP_DAYS + 1).coerceAtLeast(0)
        val pruned = rows.keys.removeAll { it.first !in from..day }
        val key = day to kind
        val current = rows[key] ?: NetCounts()
        val updated = current.plus(status)
        val changed = kind != NetworkKind.UNKNOWN && updated != current
        if (changed) {
            rows[key] = updated
        }
        return changed || pruned
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
        const val MAX_BYTES = 8192
        private const val MAX_ROWS = KEEP_DAYS * 5 // Every known transport, once per local day.
        private const val FIELDS = 5
        private const val MIN_CHECKS = 5
        private const val VPN_GOOD = 95.0
        private const val DIRECT_BAD = 80.0
        private const val INSIGHT_CHECKS = 20

        /** Damaged lines are skipped: these are statistics, not the history itself. */
        fun decode(text: String, today: Long? = null): NetworkTally {
            if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return NetworkTally()
            val lines = text.lineSequence().filter { it.isNotBlank() }.take(MAX_ROWS + 1).toList()
            val rows = if (lines.size <= MAX_ROWS) lines.mapNotNull(::row).toMap() else emptyMap()
            val last = today ?: rows.keys.maxOfOrNull { it.first } ?: -1L
            val from = (last - KEEP_DAYS + 1).coerceAtLeast(0)
            return NetworkTally(if (last >= 0) rows.filterKeys { it.first in from..last } else emptyMap())
        }

        private fun row(line: String): Pair<Pair<Long, NetworkKind>, NetCounts>? {
            val p = line.split(',')
            val kind = NetworkKind.entries.firstOrNull { it.name == p.getOrNull(1) && it != NetworkKind.UNKNOWN }
            val day = p.firstOrNull()?.toLongOrNull()
            val n = p.drop(2).mapNotNull { it.toLongOrNull()?.takeIf { v -> v in 0..MAX_NETWORK_COUNT } }
            val valid = kind != null && day != null && p.size == FIELDS && n.size == FIELDS - 2
            return if (valid) (day to kind) to NetCounts(n[0], n[1], n[2]) else null
        }

        fun label(kind: NetworkKind): TextValue = when (kind) {
            NetworkKind.WIFI -> textLiteral("Wi-Fi")
            NetworkKind.CELLULAR -> textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_MOBILE)
            NetworkKind.VPN -> textLiteral("VPN")
            NetworkKind.ETHERNET -> textLiteral("Ethernet")
            NetworkKind.OTHER, NetworkKind.UNKNOWN -> textOf(TextKey.COPY_PRIMARY_ANDROID_PROBE_OTHER)
        }

        /** Lines for the statistics card; null when fewer than two networks have enough checks to compare. */
        fun describe(totals: Map<NetworkKind, NetCounts>, days: Int = 1): TextValue? {
            val shown = totals.filterValues { it.total >= MIN_CHECKS }.toSortedMap()
            if (shown.size < 2) return null
            val parts = textJoin(shown.map { (kind, c) ->
                label(kind) + " " + textOf(TextKey.PERCENT, TextArgument.Decimal(c.okPercent))
            }, " · ")
            val vpnGood = shown[NetworkKind.VPN]?.let { it.total >= INSIGHT_CHECKS && it.okPercent >= VPN_GOOD } == true
            val directBad = shown.filterKeys { it != NetworkKind.VPN }.values
                .any { it.total >= INSIGHT_CHECKS && it.okPercent < DIRECT_BAD }
            val period = if (days <= 1) textOf(TextKey.HISTORY_NETWORK_TODAY)
                else textOf(TextKey.HISTORY_NETWORK_CALENDAR_DAYS, textArgument(days.coerceIn(1, KEEP_DAYS)))
            return textLines(listOfNotNull(textOf(TextKey.HISTORY_BY_NETWORK,
                TextArgument.Value(period), TextArgument.Value(parts)),
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
        val text = try {
            file(ctx).takeIf { it.isFile && it.length() <= NetworkTally.MAX_BYTES }?.inputStream()?.use {
                BoundedInput.read(it, NetworkTally.MAX_BYTES).toString(Charsets.UTF_8)
            }.orEmpty()
        } catch (_: Exception) { "" }
        NetworkTally.decode(text, day(System.currentTimeMillis())).also { tally = it }
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
        NetworkTally.describe(load(ctx).totals(day(now), days), days)

    /** A restored history starts with fresh network statistics. */
    @Synchronized fun clear(ctx: Context) {
        tally = NetworkTally()
        dirty = false
        try { file(ctx).delete() } catch (_: Exception) { /* nothing to clear */ }
    }
}
