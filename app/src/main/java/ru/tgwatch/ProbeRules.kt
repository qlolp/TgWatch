package ru.tgwatch

/**
 * Правила, по которым HTTP-ответ считается «сервер жив».
 * 5xx — это даун CDN/бэкенда, а не «Telegram доступен».
 * 2xx/3xx и клиентские 4xx (включая 404 на корне api) — сервер ответил.
 */
object ProbeRules {

    const val BAD_INTERVAL_SEC = 10
    const val SCREEN_OFF_INTERVAL_SEC = 60

    fun isReachableHttpCode(code: Int): Boolean = code in 100..499

    fun describeHttpFailure(code: Int): String = when (code) {
        in 500..599 -> "сервер вернул ошибку HTTP $code"
        else -> "некорректный ответ сервера (HTTP $code)"
    }

    /**
     * Какой интервал реально ждём до следующей проверки.
     * При погашенном экране не чаще минуты (если Telegram доступен),
     * чтобы ночной exact-alarm не ел батарею.
     */
    fun nextIntervalSec(intervalSec: Int, bad: Boolean, screenOffSlow: Boolean): Int {
        if (bad) return minOf(intervalSec, BAD_INTERVAL_SEC)
        return if (screenOffSlow) maxOf(intervalSec, SCREEN_OFF_INTERVAL_SEC) else intervalSec
    }

    /**
     * Проверка устарела, если прошло больше трёх ожидаемых интервалов.
     * [slowExpected] — экран может быть выключен, тогда ждём минимум минуту.
     */
    fun isStale(checkedAt: Long, now: Long, intervalSec: Int, slowExpected: Boolean = false): Boolean {
        if (checkedAt <= 0L) return true
        val expected = maxOf(intervalSec, if (slowExpected) SCREEN_OFF_INTERVAL_SEC else BAD_INTERVAL_SEC)
        return now - checkedAt > expected * 3L * 1000L
    }

    /** Минуты без проверок между первой и последней записанной. */
    fun unmonitoredMinutes(recordedMinutes: LongArray): Int {
        if (recordedMinutes.size < 2) return 0
        val span = recordedMinutes.last() - recordedMinutes.first() + 1
        return (span - recordedMinutes.size).toInt().coerceAtLeast(0)
    }

    /** «23:00–08:00» для подписи переключателя тихих часов. */
    fun quietHoursLabel(startHour: Int, endHour: Int): String =
        String.format(java.util.Locale.ROOT, "%02d:00–%02d:00", startHour.coerceIn(0, 23), endHour.coerceIn(0, 23))

    /** Тихие часы: например 23→8 пересекает полночь. */
    fun inQuietHours(hourOfDay: Int, startHour: Int, endHour: Int): Boolean {
        val h = hourOfDay.coerceIn(0, 23)
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        if (start == end) return false
        return if (start < end) h in start until end else h >= start || h < end
    }
}
