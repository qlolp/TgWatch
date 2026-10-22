package ru.tgwatch

enum class RegionalRoute { WIFI, CELLULAR, VPN, ETHERNET, OTHER, UNKNOWN, NONE }
enum class RegionalEventKind { NETWORK, COUNTRY, PUBLIC }

/** Fixed metadata only. Scope is a random process-local connection token, never a network handle/hash. */
data class RegionalJournalEntry(val kind: RegionalEventKind, val at: Long, val scope: String,
    val route: RegionalRoute, val country: String? = null, val countryAt: Long? = null,
    val service: RegionalService? = null, val status: RegionalStatus? = null,
    val confirmed: Boolean = false, val httpCode: Int? = null, val failure: RegionalFailure? = null)

object RegionalJournal {
    const val MAX_ROWS = 1_000
    const val RETENTION_MS = 7 * 24 * 60 * 60_000L

    fun retained(rows: List<RegionalJournalEntry>, entry: RegionalJournalEntry?, now: Long): List<RegionalJournalEntry> {
        require(now >= 0)
        entry?.let(RegionalJournalCodec::validate)
        val cutoff = (now - RETENTION_MS).coerceAtLeast(0)
        val kept = rows.filter { it.at >= cutoff }
        val previous = entry?.let { next -> kept.lastOrNull {
            it.kind == next.kind && it.scope == next.scope && it.service == next.service
        } }
        val append = entry != null && entry.copy(at = previous?.at ?: entry.at) != previous
        return (if (append) kept + entry!! else kept).takeLast(MAX_ROWS)
    }
}

/** Versioned, strict fixed-column format; unknown/truncated fields invalidate the whole file. */
object RegionalJournalCodec {
    const val MAX_BYTES = 512 * 1024
    private const val HEADER = "TGRJ1\n"
    private const val COLUMNS = 11
    private val scopePattern = Regex("[0-9a-f]{32}")
    private val numberPattern = Regex("0|[1-9][0-9]*")

    fun validate(row: RegionalJournalEntry) {
        require(row.at >= 0 && scopePattern.matches(row.scope)) { "Invalid regional identity" }
        require((row.country == null) == (row.countryAt == null)) { "Incomplete country metadata" }
        require(row.country == null || EgressCountry.isValid(row.country)) { "Invalid country" }
        require(row.countryAt == null || row.countryAt in 0..row.at) { "Invalid country time" }
        require(row.httpCode == null || row.httpCode in 100..599) { "Invalid HTTP code" }
        require(!row.confirmed || row.status == RegionalStatus.REGION_RESTRICTED) { "Invalid confirmation" }
        require(row.failure == null || row.status in setOf(RegionalStatus.NETWORK_ERROR, RegionalStatus.UNKNOWN))
        when (row.kind) {
            RegionalEventKind.PUBLIC -> require(row.service != null && row.status != null)
            RegionalEventKind.COUNTRY -> require(row.country != null && row.service == null && row.status == null)
            RegionalEventKind.NETWORK -> require(row.service == null && row.status == null)
        }
        require(row.kind == RegionalEventKind.PUBLIC ||
            (!row.confirmed && row.httpCode == null && row.failure == null))
    }

    fun encode(rows: List<RegionalJournalEntry>): String {
        require(rows.size <= RegionalJournal.MAX_ROWS) { "Too many regional events" }
        rows.forEach(::validate)
        val text = HEADER + rows.joinToString("") { row -> listOf(row.kind.name, row.at, row.scope,
            row.route.name, row.country ?: "-", row.countryAt ?: "-", row.service?.wire ?: "-",
            row.status?.name ?: "-", if (row.confirmed) "1" else "0", row.httpCode ?: "-",
            row.failure?.name ?: "-").joinToString("\t") + "\n" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Regional journal too large" }
        return text
    }

    fun decode(text: String): List<RegionalJournalEntry> {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        require(text.startsWith(HEADER) && text.endsWith('\n')) { "Unsupported regional journal" }
        if (text == HEADER) return emptyList()
        val lines = text.substring(HEADER.length, text.length - 1).split('\n')
        require(lines.size <= RegionalJournal.MAX_ROWS)
        return lines.map(::decodeRow)
    }

    private fun number(value: String): Long {
        require(numberPattern.matches(value)) { "Invalid regional number" }
        return value.toLongOrNull() ?: throw IllegalArgumentException("Regional number overflow")
    }

    private fun decodeRow(line: String): RegionalJournalEntry {
        val fields = line.split('\t')
        require(fields.size == COLUMNS && fields[8] in setOf("0", "1")) { "Invalid regional row" }
        val service = if (fields[6] == "-") null else RegionalService.fromWire(fields[6])
            ?: throw IllegalArgumentException("Unknown service")
        val row = RegionalJournalEntry(RegionalEventKind.valueOf(fields[0]), number(fields[1]), fields[2],
            RegionalRoute.valueOf(fields[3]), fields[4].takeUnless { it == "-" },
            fields[5].takeUnless { it == "-" }?.let(::number), service,
            fields[7].takeUnless { it == "-" }?.let(RegionalStatus::valueOf), fields[8] == "1",
            fields[9].takeUnless { it == "-" }?.let { require(number(it) <= 599); number(it).toInt() },
            fields[10].takeUnless { it == "-" }?.let(RegionalFailure::valueOf))
        validate(row)
        return row
    }
}
