package ru.tgwatch

data class RegionalComparisonSnapshot(val label: String, val at: Long, val connection: RegionalConnection,
    val readings: Map<RegionalService, RegionalJournalEntry?>) {
    val complete: Boolean get() = readings.isNotEmpty() && readings.values.all { it != null }
}

data class RegionalComparisonPair(val a: RegionalComparisonSnapshot, val b: RegionalComparisonSnapshot) {
    val sameConnection: Boolean get() = a.connection.token == b.connection.token
}

enum class RegionalComparisonSaveOutcome { SAVED, FAILED, REPLACED }

object RegionalComparisonCompletion {
    fun outcome(requestedGeneration: Long, currentGeneration: Long, saved: Boolean): RegionalComparisonSaveOutcome =
        when {
            requestedGeneration != currentGeneration -> RegionalComparisonSaveOutcome.REPLACED
            saved -> RegionalComparisonSaveOutcome.SAVED
            else -> RegionalComparisonSaveOutcome.FAILED
        }
}

/** Only post-request observations on the captured connection can fill a side. Missing stays missing. */
class RegionalComparisonCollector(private val startElapsed: Long, val context: RegionalConnection,
    services: Set<RegionalService>, private val label: String, private val startedAt: Long) {
    private val selected = services.toSet()
    private val readings = mutableMapOf<RegionalService, RegionalJournalEntry>()
    private val elapsed = mutableMapOf<RegionalService, Long>()
    var invalidated = false
        private set
    val measured: Int get() = readings.size
    val total: Int get() = selected.size

    init {
        require(startElapsed >= 0 && startedAt >= 0 && selected.isNotEmpty())
        RegionalComparisonRules.label(label)
        RegionalComparisonRules.connection(context, startedAt)
    }

    fun offer(row: RegionalJournalEntry, checkedElapsed: Long, nowElapsed: Long,
        current: RegionalConnection, enabled: Set<RegionalService>): Boolean {
        if (!current(nowElapsed, current, enabled) || !validObservation(row, checkedElapsed, nowElapsed)) return false
        val service = requireNotNull(row.service)
        val newer = checkedElapsed > (elapsed[service] ?: -1)
        if (newer) { readings[service] = row; elapsed[service] = checkedElapsed }
        return newer
    }

    private fun validObservation(row: RegionalJournalEntry, checked: Long, now: Long): Boolean =
        checked > startElapsed && checked <= now && checked - startElapsed <= DEADLINE_MS &&
            row.at >= startedAt && row.scope == context.token && row.route == context.route &&
            row.kind == RegionalEventKind.PUBLIC && row.service in selected &&
            runCatching { RegionalJournalCodec.validate(row) }.isSuccess

    fun finish(nowElapsed: Long, at: Long, current: RegionalConnection,
        enabled: Set<RegionalService>): RegionalComparisonSnapshot? {
        val clockValid = at >= startedAt && readings.values.none { it.at > at }
        if (!clockValid) invalidated = true
        val done = readings.size == selected.size || nowElapsed >= startElapsed &&
            nowElapsed - startElapsed >= DEADLINE_MS
        return if (!current(nowElapsed, current, enabled) || !done) null else {
            val known = current.country != null && EgressCountry.isValid(current.country) &&
                current.countryAt?.let { it in 0..at } == true
            val connection = if (known) current else current.copy(country = null, countryAt = null)
            RegionalComparisonSnapshot(label, at, connection, selected.associateWith { readings[it] })
        }
    }

    private fun current(now: Long, connection: RegionalConnection, enabled: Set<RegionalService>): Boolean {
        val sameContext = connection.token == context.token && connection.route == context.route
        if (now < startElapsed || !sameContext || enabled != selected) invalidated = true
        return !invalidated
    }

    companion object { const val DEADLINE_MS = 75_000L }
}

internal object RegionalComparisonRules {
    private const val LABEL_LIMIT = 40
    fun label(label: String) {
        require(label.isNotBlank() && label.length <= LABEL_LIMIT && label.none {
            it.isISOControl() || it == '\u2028' || it == '\u2029'
        }) { "Invalid comparison label" }
    }

    fun connection(connection: RegionalConnection, at: Long) {
        RegionalJournalCodec.validate(RegionalJournalEntry(RegionalEventKind.NETWORK, at, connection.token,
            connection.route, connection.country, connection.countryAt))
    }

    fun snapshot(side: RegionalComparisonSnapshot) {
        label(side.label)
        connection(side.connection, side.at)
        require(side.readings.isNotEmpty() && side.readings.size <= RegionalService.entries.size)
        side.readings.forEach { (service, row) -> if (row != null) {
            RegionalJournalCodec.validate(row)
            require(row.kind == RegionalEventKind.PUBLIC && row.service == service && row.at <= side.at &&
                row.scope == side.connection.token && row.route == side.connection.route)
        } }
    }
}
