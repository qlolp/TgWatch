package ru.tgwatch

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Итоги проверок по минутам за последние сутки: для графика на экране
 * и для процента доступности. Хранится в файле, поэтому переживает
 * перезапуск приложения и телефона.
 */
object History {

    class Minute(
        val minute: Long,       // номер минуты с 1970 года
        var ok: Int = 0,        // проверок, когда Telegram ответил
        var fail: Int = 0,      // проверок, когда интернет есть, а Telegram нет
        var offline: Int = 0,   // проверок без интернета
        var latencySum: Long = 0L,
    ) {
        val total get() = ok + fail + offline
        val avgLatency get() = if (ok > 0) latencySum / ok else -1L

        /** Минута «красная», только если сбоев Telegram не меньше успешных. */
        val isFailDominant get() = fail > 0 && fail >= ok && fail >= offline

        /** Минута «оранжевая» — в основном без интернета. */
        val isOfflineDominant get() = offline > 0 && offline >= ok && offline > fail

        fun copy() = Minute(minute, ok, fail, offline, latencySum)
    }

    enum class Kind { OK, FAIL, OFFLINE }

    data class DayStats(
        val uptimePercent: Double,      // Telegram: ok/(ok+fail), без offline
        val checks: Long,
        val telegramChecks: Long,
        val failMinutes: Int,
        val offlineMinutes: Int,
        val longestOutageMin: Int,
        val avgLatencyMs: Long,
        val unmonitoredMinutes: Int,
    )

    private const val FILE = "history.csv"
    private const val KEEP_MINUTES = 24 * 60
    private const val FLUSH_EVERY = 8
    private const val TAG = "TgWatch"

    private val minutes = ArrayDeque<Minute>()
    private var loaded = false
    private var unsaved = 0

    /** Меняется после каждой записи: экран по нему понимает, что пора перерисовать график. */
    @Volatile
    var version = 0L
        private set

    @Synchronized
    fun record(ctx: Context, now: Long, kind: Kind, latencyMs: Long) {
        load(ctx)
        val minute = now / 60_000L
        val prev = minutes.lastOrNull()
        val newMinute = prev == null || prev.minute != minute
        val last: Minute = if (prev != null && !newMinute) prev else Minute(minute).also { minutes.addLast(it) }
        when (kind) {
            Kind.OK -> {
                last.ok++
                last.latencySum += latencyMs.coerceAtLeast(0L)
            }
            Kind.FAIL -> last.fail++
            Kind.OFFLINE -> last.offline++
        }
        while (minutes.isNotEmpty() && minutes.first().minute <= minute - KEEP_MINUTES) minutes.removeFirst()
        version++
        unsaved++
        // На диск: при новой минуте или каждые несколько проверок (защита от kill).
        if (newMinute || unsaved >= FLUSH_EVERY) {
            save(ctx)
            unsaved = 0
        }
    }

    /** Копия минут за последние [count] минут (старые сначала). */
    @Synchronized
    fun lastMinutes(ctx: Context, count: Int, now: Long = System.currentTimeMillis()): List<Minute> {
        load(ctx)
        val from = now / 60_000L - count + 1
        return minutes.filter { it.minute >= from }.map { it.copy() }
    }

    /**
     * Доступность Telegram за сутки: только проверки, когда интернет был.
     * Offline минуты не портят процент.
     */
    @Synchronized
    fun uptimePercent(ctx: Context): Double {
        load(ctx)
        var ok = 0L
        var fail = 0L
        for (m in minutes) {
            ok += m.ok
            fail += m.fail
        }
        val total = ok + fail
        return if (total == 0L) -1.0 else ok * 100.0 / total
    }

    /** Сводка за сутки для экрана. */
    @Synchronized
    fun dayStats(ctx: Context): DayStats? {
        load(ctx)
        if (minutes.isEmpty()) return null
        var ok = 0L
        var fail = 0L
        var offline = 0L
        var latencySum = 0L
        var latencyN = 0L
        var failMinutes = 0
        var offlineMinutes = 0
        var longest = 0
        var streak = 0
        for (m in minutes) {
            ok += m.ok
            fail += m.fail
            offline += m.offline
            if (m.ok > 0) {
                latencySum += m.latencySum
                latencyN += m.ok
            }
            when {
                m.isFailDominant -> {
                    failMinutes++
                    streak++
                    if (streak > longest) longest = streak
                }
                m.isOfflineDominant -> {
                    offlineMinutes++
                    streak = 0
                }
                else -> streak = 0
            }
        }
        val telegramChecks = ok + fail
        if (ok + fail + offline == 0L) return null
        return DayStats(
            uptimePercent = if (telegramChecks == 0L) -1.0 else ok * 100.0 / telegramChecks,
            checks = ok + fail + offline,
            telegramChecks = telegramChecks,
            failMinutes = failMinutes,
            offlineMinutes = offlineMinutes,
            longestOutageMin = longest,
            avgLatencyMs = if (latencyN > 0) latencySum / latencyN else -1L,
            unmonitoredMinutes = ProbeRules.unmonitoredMinutes(minutes.map { it.minute }.toLongArray()),
        )
    }

    fun exportSummary(ctx: Context): String {
        val stats = dayStats(ctx) ?: return "За сутки данных ещё нет."
        val lines = mutableListOf("TG Монитор — сводка за сутки")
        if (stats.uptimePercent >= 0) {
            lines += "Доступность Telegram: ${formatPercent(stats.uptimePercent)} (${stats.telegramChecks} проверок с интернетом)"
        }
        lines += "Всего проверок: ${stats.checks}"
        if (stats.avgLatencyMs >= 0) lines += "Среднее время ответа: ${stats.avgLatencyMs} мс"
        if (stats.failMinutes > 0) lines += "Минут без Telegram: ${stats.failMinutes}"
        if (stats.offlineMinutes > 0) lines += "Минут без интернета: ${stats.offlineMinutes}"
        if (stats.longestOutageMin > 0) lines += "Самый долгий простой Telegram: ${stats.longestOutageMin} мин"
        if (stats.unmonitoredMinutes > 0) lines += "Минут без проверки (служба спала): ${stats.unmonitoredMinutes}"
        if (stats.failMinutes == 0 && stats.offlineMinutes == 0) lines += "Сбоев не было"
        return lines.joinToString("\n")
    }

    @Synchronized
    fun flush(ctx: Context) {
        if (loaded) {
            save(ctx)
            unsaved = 0
        }
    }

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return
            val oldest = System.currentTimeMillis() / 60_000L - KEEP_MINUTES
            f.forEachLine { line ->
                val p = line.split(',')
                if (p.size == 5) {
                    val m = Minute(p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toLong())
                    if (m.minute > oldest) minutes.addLast(m)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "history load", e)
            minutes.clear()
        }
    }

    private fun save(ctx: Context) {
        try {
            val text = minutes.joinToString("\n") {
                "${it.minute},${it.ok},${it.fail},${it.offline},${it.latencySum}"
            }
            val dir = ctx.filesDir
            val target = File(dir, FILE)
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(text)
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "history: could not delete old file")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "history save", e)
        }
    }
}

/** Журнал смен статуса, новые записи сверху. Хранится в настройках, поэтому не пропадает после перезапуска. */
object EventLog {
    private const val KEY = "log"
    private const val MAX = 150

    @Volatile
    private var items: List<String>? = null

    fun all(ctx: Context): List<String> {
        items?.let { return it }
        synchronized(this) {
            val loaded = Prefs.sp(ctx).getString(KEY, null)
                ?.split('\n')
                ?.filter { it.isNotBlank() }
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
        Prefs.sp(ctx).edit().putString(KEY, updated.joinToString("\n")).apply()
    }

    @Synchronized
    fun clear(ctx: Context) {
        items = emptyList()
        Prefs.sp(ctx).edit().remove(KEY).apply()
    }

    fun exportText(ctx: Context): String {
        val log = all(ctx)
        return if (log.isEmpty()) "Журнал пуст" else log.joinToString("\n")
    }
}
