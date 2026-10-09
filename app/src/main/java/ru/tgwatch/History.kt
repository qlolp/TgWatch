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

        fun copy() = Minute(minute, ok, fail, offline, latencySum)
    }

    enum class Kind { OK, FAIL, OFFLINE }

    private const val FILE = "history.csv"
    private const val KEEP_MINUTES = 24 * 60

    private val minutes = ArrayDeque<Minute>()
    private var loaded = false

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
        // На диск пишем не чаще раза в минуту: при переходе на новую минуту
        // сохраняем всё, что накопилось за предыдущую.
        if (newMinute) save(ctx)
    }

    /** Копия минут за последние [count] минут (старые сначала). */
    @Synchronized
    fun lastMinutes(ctx: Context, count: Int, now: Long = System.currentTimeMillis()): List<Minute> {
        load(ctx)
        val from = now / 60_000L - count + 1
        return minutes.filter { it.minute >= from }.map { it.copy() }
    }

    /** Доля успешных проверок за сутки, в процентах; -1, если проверок не было. */
    @Synchronized
    fun uptimePercent(ctx: Context): Double {
        load(ctx)
        var ok = 0L
        var total = 0L
        for (m in minutes) {
            ok += m.ok
            total += m.total
        }
        return if (total == 0L) -1.0 else ok * 100.0 / total
    }

    @Synchronized
    fun flush(ctx: Context) {
        if (loaded) save(ctx)
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
            Log.w("TgWatch", "history load", e)
            minutes.clear()
        }
    }

    private fun save(ctx: Context) {
        try {
            val text = minutes.joinToString("\n") {
                "${it.minute},${it.ok},${it.fail},${it.offline},${it.latencySum}"
            }
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(ctx.filesDir, FILE))
        } catch (e: Exception) {
            Log.w("TgWatch", "history save", e)
        }
    }
}

/** Журнал смен статуса, новые записи сверху. Хранится в настройках, поэтому не пропадает после перезапуска. */
object EventLog {
    private const val KEY = "log"
    private const val MAX = 100

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
}
