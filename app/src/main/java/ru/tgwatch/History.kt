package ru.tgwatch

import android.content.Context
import android.os.UserManager
import android.os.SystemClock
import android.util.Log
import java.io.File

/** Seven-day bounded timeline in device-protected storage. */
object History {
    class Minute(val minute: Long, var ok: Int = 0, var fail: Int = 0, var offline: Int = 0,
        latencySum: Long = 0L, var partial: Int = 0, var unknown: Int = 0) {
        private var latencyTotal = LatencyTotal(latencySum)
        var latencySum: Long
            get() = latencyTotal.saturatedSum
            set(value) { latencyTotal = LatencyTotal(value) }
        val total get() = ok + fail + offline + partial + unknown
        val avgLatency get() = latencyTotal.average(ok)
        val isFailDominant get() = fail > 0 && fail >= ok && fail >= offline && fail >= partial && fail >= unknown
        val isOfflineDominant get() = offline > 0 && offline >= ok && offline > fail && offline >= partial && offline >= unknown
        val isPartialDominant get() = partial > 0 && partial >= ok && partial >= fail && partial >= offline
        val isUnknownDominant get() = unknown > 0 && unknown >= ok && unknown >= fail && unknown >= offline && unknown >= partial
        fun addLatency(latency: Long) { ok++; latencyTotal.add(latency) }
        fun copy() = Minute(minute, ok, fail, offline, 0L, partial, unknown).also {
            it.latencyTotal = latencyTotal.copy()
        }
        companion object {
            fun merge(minute: Long, values: List<Minute>): Minute = Minute(minute,
                values.sumOf { it.ok }, values.sumOf { it.fail }, values.sumOf { it.offline },
                0L, values.sumOf { it.partial }, values.sumOf { it.unknown }).also { merged ->
                    values.forEach { merged.latencyTotal.merge(it.latencyTotal) }
                }
        }
    }
    enum class Kind { OK, FAIL, OFFLINE, PARTIAL, UNKNOWN }
    data class DayStats(val uptimePercent: Double, val checks: Long, val telegramChecks: Long,
        val failMinutes: Int, val offlineMinutes: Int, val longestOutageMin: Int,
        val avgLatencyMs: Long, val unmonitoredMinutes: Int)

    private const val FILE = "history-v2.csv"
    private const val MAX_APPEND_BYTES = 128L
    private const val KEEP_MS = 7 * 24 * 60 * 60_000L
    private var samples = mutableListOf<Observation>()
    private var loaded = false
    private var migrated = false
    private var dirty = false
    private var lastCompacted = 0L
    private var physicalRows = 0
    @Volatile private var failure: HistoryFailure? = null
    val healthFailure: HistoryFailure? get() = failure
    private val compaction = HistoryCompaction()
    private val statsCache = mutableMapOf<String, TimeStats>()
    private val preparation = HistoryPreparationCache()
    private var appliedPreparation: HistoryPreparationCache.Prepared? = null
    private val appendSync = HistoryStorage.AppendSyncPolicy()
    @Volatile var version = 0L
        private set

    private fun file(ctx: Context) = File(ctx.applicationContext.createDeviceProtectedStorageContext().filesDir, FILE)
    private fun load(ctx: Context) {
        if (!loaded) {
            val stored = HistoryFileReader.readCounted(file(ctx))
            samples = stored.rows.toMutableList()
            physicalRows = stored.physicalRows
            preparation.clear()
            appliedPreparation = null
            statsCache.clear()
            dirty = false
            loaded = true
            lastCompacted = System.currentTimeMillis()
            compaction.savedReplacement(lastCompacted)
            failure = null
        }
        // Retry after unlock. Do not mark migration complete or delete the source on failure.
        if (!migrated && ctx.getSystemService(UserManager::class.java).isUserUnlocked) {
            val dp = ctx.applicationContext.createDeviceProtectedStorageContext().filesDir
            val marker = File(dp, "history-migrated-v2")
            val legacy = File(ctx.applicationContext.filesDir, "history.csv")
            val hadMarker = marker.exists()
            val result = HistoryStorage.migrateLegacy(legacy, file(ctx), marker, samples)
            if (samples != result.samples) {
                preparation.clear()
                appliedPreparation = null
                statsCache.clear()
                version++
            }
            samples = result.samples.toMutableList()
            migrated = result.complete
            if (result.complete && !hadMarker) physicalRows = samples.size
        }
    }
    private fun prune(now: Long) {
        val prepared = preparation.prepare(samples, version, now - KEEP_MS)
        if (prepared === appliedPreparation) return
        if (prepared.rows != samples) {
            samples = prepared.rows.toMutableList()
            compaction.onPruned(now)
            version++
            statsCache.clear()
            preparation.acceptRevision(version)
        }
        appliedPreparation = prepared
    }
    private fun ensureClock(ctx: Context, now: Long) {
        val latest = preparation.prepare(samples, version, now - KEEP_MS).latestAt
        if (latest == null || latest <= now) return
        val rolled = Timeline.clockRollback(samples, now)
        if (rolled !== samples) {
            samples = rolled.toMutableList()
            dirty = true
            version++
            // The epoch marker must fit the logical cap before its durable replacement.
            prune(now)
            flush(ctx, now)
        }
    }
    private fun ready(ctx: Context): Boolean = try { load(ctx); true } catch (e: Exception) {
        failure = HistoryFailure.READ
        Log.w("TgWatch", "history unavailable; preserving existing data", e)
        false
    }
    @Synchronized fun record(ctx: Context, now: Long, kind: Kind, latencyMs: Long, expectedSec: Int = 30) {
        if (!ready(ctx)) return
        ensureClock(ctx, now)
        prune(now)
        // Bound inferred coverage to one expected interval plus the bounded network-check budget.
        val end = now + (expectedSec.coerceIn(10, 300) * 1000L + 15_000L)
        val status = when (kind) { Kind.FAIL -> "TG_DOWN"; Kind.OFFLINE -> "NO_NETWORK"; else -> kind.name }
        samples.add(Observation(now, end, status, latencyMs, samples.maxOfOrNull { it.clockEpoch } ?: 0))
        version++
        // Adding a row can exceed the row cap or replace a same-time epoch marker.
        prune(now)
        try {
            if (dirty || compaction.needsReplacement(now, physicalRows + 1, file(ctx).length() + MAX_APPEND_BYTES)) {
                HistoryStorage.write(file(ctx), samples)
                dirty = false
                lastCompacted = now
                physicalRows = samples.size
                compaction.savedReplacement(now)
                appendSync.saved(status, SystemClock.elapsedRealtime(), true)
            } else {
                val elapsed = SystemClock.elapsedRealtime()
                val sync = appendSync.needsSync(status, elapsed)
                HistoryStorage.append(file(ctx), samples.last(), sync)
                physicalRows++
                compaction.savedAppend()
                appendSync.saved(status, elapsed, sync)
            }
            failure = null
        } catch (e: Exception) {
            dirty = true
            compaction.failedWrite()
            failure = HistoryFailure.WRITE
            Log.w("TgWatch", "history save", e)
        }
    }
    @Synchronized fun endSession(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (!ready(ctx)) return
        ensureClock(ctx, now)
        val epoch = samples.maxOfOrNull { it.clockEpoch } ?: 0L
        samples = samples.mapNotNull { s ->
            if (s.clockEpoch != epoch) s else if (s.at >= now) s.copy(kind = "UNKNOWN")
            else s.copy(until = minOf(s.until, now))
        }.toMutableList()
        dirty = true
        version++
        flush(ctx, now)
    }
    @Synchronized fun stats(ctx: Context, days: Int = 1, now: Long = System.currentTimeMillis()): TimeStats =
        readStats(ctx, days, now).valueOrThrow()

    @Synchronized fun readStats(ctx: Context, days: Int = 1,
        now: Long = System.currentTimeMillis()): HistoryRead<TimeStats> {
        if (!ready(ctx)) {
            val last = statsCache.entries.lastOrNull { it.key.endsWith(":${days.coerceIn(1, 7)}") }?.value
            return HistoryRead.Unavailable(last, failure ?: HistoryFailure.READ)
        }
        val value = computeStats(ctx, days, now)
        val problem = failure
        return if (problem == null) HistoryRead.Ready(value) else HistoryRead.Unavailable(value, problem)
    }

    private fun computeStats(ctx: Context, days: Int, now: Long): TimeStats {
        ensureClock(ctx, now)
        prune(now)
        val cacheKey = "$version:${now / 60_000L}:${days.coerceIn(1,7)}"
        statsCache[cacheKey]?.let { return it }
        if (statsCache.size > 4) statsCache.clear()
        val checks = preparation.prepare(samples, version, now - KEEP_MS).checks
        return Timeline.stats(checks, now - days.coerceIn(1,7) * 86_400_000L, now)
            .also { statsCache[cacheKey] = it }
    }
    @Synchronized fun incidents(ctx: Context, days: Int = 7,
        now: Long = System.currentTimeMillis()): List<OutageEpisode> = readIncidents(ctx, days, now).valueOrThrow()

    @Synchronized fun readIncidents(ctx: Context, days: Int = 7,
        now: Long = System.currentTimeMillis()): HistoryRead<List<OutageEpisode>> {
        if (!ready(ctx)) return HistoryRead.Unavailable(null, failure ?: HistoryFailure.READ)
        ensureClock(ctx, now)
        prune(now)
        val checks = preparation.prepare(samples, version, now - KEEP_MS).checks
        val value = OutageEpisodes.derivePrepared(checks, now - days.coerceIn(1, 7) * 86_400_000L, now)
        val problem = failure
        return if (problem == null) HistoryRead.Ready(value) else HistoryRead.Unavailable(value, problem)
    }
    @Synchronized fun uptimePercent(ctx: Context): Double = stats(ctx).uptimePercent
    @Synchronized fun recovery(ctx: Context, checkedAt: Long, partialEnabled: Boolean): RecoveryEvent? =
        if (ready(ctx)) Timeline.recovery(samples, checkedAt, partialEnabled) else null
    @Synchronized fun dayStats(ctx: Context): DayStats? {
        val s = stats(ctx)
        if (s.checks == 0) return null
        return DayStats(s.uptimePercent, s.checks.toLong(), s.checks.toLong(), (s.downMs / 60_000).toInt(),
            (s.offlineMs / 60_000).toInt(), (s.longestOutageMs / 60_000).toInt(), s.avgLatencyMs, (s.unknownMs / 60_000).toInt())
    }
    @Synchronized fun lastMinutes(ctx: Context, count: Int, now: Long = System.currentTimeMillis()): List<Minute> {
        if (!ready(ctx)) return emptyList()
        ensureClock(ctx, now)
        prune(now)
        val from = now / 60_000L - count + 1
        val bins = sortedMapOf<Long, Minute>()
        for (s in preparation.prepare(samples, version, now - KEEP_MS).checks) {
            if (s.at / 60_000L < from || s.at > now) continue
            val m = bins.getOrPut(s.at / 60_000L) { Minute(s.at / 60_000L) }
            when (s.kind) {
                "OK" -> m.addLatency(s.latencyMs)
                "TG_DOWN" -> m.fail++
                "NO_NETWORK" -> m.offline++
                "PARTIAL" -> m.partial++
                else -> m.unknown++
            }
        }
        return bins.values.map { it.copy() }
    }
    @Synchronized fun exportCsv(ctx: Context, days: Int = 7): String {
        check(ready(ctx)) { Lang.t("История временно недоступна", "History is temporarily unavailable") }
        val now = System.currentTimeMillis()
        ensureClock(ctx, now)
        prune(now)
        return Timeline.csv(samples, now - days.coerceIn(1,7) * 86_400_000L, now)
    }
    @Synchronized fun snapshot(ctx: Context): List<Observation> {
        check(ready(ctx)) { Lang.t("История временно недоступна", "History is temporarily unavailable") }
        prune(System.currentTimeMillis())
        return samples.toList()
    }
    @Synchronized fun replaceSnapshot(ctx: Context, rows: List<Observation>) {
        val now = System.currentTimeMillis()
        dirty = true
        val retained = HistoryStorage.replace(file(ctx), rows, now - KEEP_MS)
        // A restored snapshot supersedes legacy history on subsequent process starts too.
        val marker = File(file(ctx).parentFile, "history-migrated-v2")
        if (!marker.exists()) java.io.FileOutputStream(marker).use {
            it.write("restored snapshot supersedes legacy history".toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        HistoryStorage.syncDirectory(marker.parentFile!!)
        samples = retained.toMutableList()
        loaded = true
        migrated = true
        dirty = false
        lastCompacted = now
        physicalRows = samples.size
        compaction.savedReplacement(now)
        failure = null
        appendSync.saved(samples.lastOrNull()?.kind, SystemClock.elapsedRealtime(), true)
        statsCache.clear()
        version++
    }
    fun exportSummary(ctx: Context, days: Int = 1): String = describeStats(stats(ctx, days), days)
    @Synchronized fun snapshot(ctx: Context, now: Long): List<Observation> {
        check(ready(ctx)) { Lang.t("История временно недоступна", "History is temporarily unavailable") }
        ensureClock(ctx,now)
        prune(now)
        val epoch = samples.maxOfOrNull { it.clockEpoch } ?: 0
        val result = samples.mapNotNull { if (it.clockEpoch != epoch || it.isEpochMetadata) it else
            if (it.at >= now) null else it.copy(until=minOf(it.until,now)) }
        // If clipping removed the current epoch's final check, retain its identity as metadata.
        return if (samples.isNotEmpty() && result.none { it.clockEpoch == epoch }) result +
            Observation(now.coerceAtLeast(0), now.coerceAtLeast(0), "UNKNOWN", clockEpoch = epoch)
            else result
    }
    @Synchronized internal fun replace(ctx: Context, replacement: List<Observation>) {
        replaceSnapshot(ctx, replacement)
    }
    fun describeStats(s: TimeStats, days: Int): String = buildString {
        append(Lang.t("TG Монитор — ", "TG Monitor — ")).append(if (days == 1) Lang.t("за сутки", "last 24 hours") else Lang.t("за неделю", "last week")).append('\n')
        if (s.uptimePercent >= 0) append(Lang.t("Оценка доступности по времени: ${formatPercent(s.uptimePercent)}\n", "Time-based availability estimate: ${formatPercent(s.uptimePercent)}\n"))
        append(Lang.t("Доступен: ${durationStr(s.okMs)}; частично: ${durationStr(s.partialMs)}\n", "Reachable: ${durationStr(s.okMs)}; partially: ${durationStr(s.partialMs)}\n"))
        append(Lang.t("Сбой Telegram: ${durationStr(s.downMs)}; нет сети: ${durationStr(s.offlineMs)}\n", "Telegram outage: ${durationStr(s.downMs)}; no internet: ${durationStr(s.offlineMs)}\n"))
        append(Lang.t("Нет данных: ${durationStr(s.unknownMs)}\n", "No data: ${durationStr(s.unknownMs)}\n"))
        append(Lang.t("Подтверждённых эпизодов сбоя Telegram: ${s.outageCount}; потерь сети: ${s.offlineCount}\n", "Confirmed Telegram outages: ${s.outageCount}; internet losses: ${s.offlineCount}\n"))
        if (s.outageCount > 0) append(if (s.lastOutageOngoing) Lang.t("Текущий сбой: ", "Current outage: ") else Lang.t("Последний наблюдаемый сбой: ", "Last observed outage: "))
            .append(recoveryDurationStr(s.lastOutageMs)).append('\n')
        append(Lang.t("Самый долгий подтверждённый сбой: ${durationStr(s.longestOutageMs)}\n", "Longest confirmed outage: ${durationStr(s.longestOutageMs)}\n"))
        append(Lang.t("Завершённых полностью наблюдённых сбоев: ${s.completedOutageCount}\n", "Completed fully observed outages: ${s.completedOutageCount}\n"))
        if (s.mttrMs >= 0) append(Lang.t("Среднее время восстановления (MTTR): ${recoveryDurationStr(s.mttrMs)}\n", "Mean time to recovery (MTTR): ${recoveryDurationStr(s.mttrMs)}\n"))
        append(Lang.t("Проверки: OK ${s.okChecks}; сбой ${s.downChecks}; частично ${s.partialChecks}; нет сети ${s.offlineChecks}; нет данных ${s.unknownChecks}\n", "Checks: OK ${s.okChecks}; outage ${s.downChecks}; partial ${s.partialChecks}; no internet ${s.offlineChecks}; no data ${s.unknownChecks}\n"))
        append(Lang.t("В MTTR входят полностью наблюдённые сбои Telegram между OK; PARTIAL может продолжать сбой, а пробелы и отсутствие сети исключаются.\n", "MTTR covers fully observed Telegram outages between OK checks; PARTIAL can extend an outage, while gaps and lost internet are excluded.\n"))
        append(Lang.t("Проверок: ${s.checks}. Промежутки между проверками оцениваются; пробелы исключены из процента.", "Checks: ${s.checks}. Time between checks is estimated; gaps are excluded from the percentage."))
    }
    @Synchronized fun flush(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (!loaded) return
        dirty = true
        preparation.clear()
        appliedPreparation = null
        try {
            HistoryStorage.write(file(ctx), samples)
            dirty = false
            lastCompacted = now
            physicalRows = samples.size
            compaction.savedReplacement(lastCompacted)
            failure = null
            appendSync.saved(samples.lastOrNull()?.kind, SystemClock.elapsedRealtime(), true)
        } catch (e: Exception) {
            compaction.failedWrite()
            failure = HistoryFailure.WRITE
            Log.w("TgWatch", "history flush", e)
        }
    }
}

/** Журнал смен статуса, новые записи сверху. Хранится в настройках, поэтому не пропадает после перезапуска. */
object EventLog {
    private const val KEY = "log"
    private const val MAX = 150

    @Volatile
    private var items: List<String>? = null
    internal fun invalidate() { items = null }

    fun all(ctx: Context): List<String> {
        items?.let { return it }
        synchronized(this) {
            val loaded = Prefs.sp(ctx).getString(KEY, null)
                ?.let { EventLogStorage.decode(it) }
                ?: emptyList()
            if (items == null) items = loaded
            return items!!
        }
    }

    @Synchronized
    fun add(ctx: Context, message: String) {
        val time = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val updated = (listOf("$time  $message") + all(ctx)).take(MAX)
        items = updated
        Prefs.sp(ctx).edit().putString(KEY, EventLogStorage.encode(updated)).apply()
    }

    @Synchronized
    fun clear(ctx: Context) {
        items = emptyList()
        Prefs.sp(ctx).edit().remove(KEY).apply()
    }

    @Synchronized
    fun replace(ctx: Context, restored: List<String>) {
        val replacement = restored.toList()
        val encoded = EventLogStorage.encode(replacement)
        check(Prefs.sp(ctx).edit().putString(KEY, encoded).commit()) { "Cannot save event log" }
        items = replacement
    }

    fun exportText(ctx: Context): String {
        val log = all(ctx)
        return if (log.isEmpty()) Lang.t("Журнал пуст", "The log is empty") else log.joinToString("\n")
    }
}

