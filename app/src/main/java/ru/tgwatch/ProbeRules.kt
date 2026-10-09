package ru.tgwatch

/**
 * Правила, по которым HTTP-ответ считается «сервер жив».
 * 5xx — это даун CDN/бэкенда, а не «Telegram доступен».
 * 2xx/3xx и клиентские 4xx (включая 404 на корне api) — сервер ответил.
 */
object ProbeRules {

    fun isReachableHttpCode(code: Int): Boolean = code in 100..499

    fun describeHttpFailure(code: Int): String = when (code) {
        in 500..599 -> "сервер вернул ошибку HTTP $code"
        else -> "некорректный ответ сервера (HTTP $code)"
    }

    /** Проверка устарела, если с неё прошло больше двух интервалов мониторинга. */
    fun isStale(checkedAt: Long, now: Long, intervalSec: Int): Boolean {
        if (checkedAt <= 0L) return true
        val limit = maxOf(intervalSec, 10) * 2L * 1000L
        return now - checkedAt > limit
    }

    /** Тихие часы: например 23→8 пересекает полночь. */
    fun inQuietHours(hourOfDay: Int, startHour: Int, endHour: Int): Boolean {
        val h = hourOfDay.coerceIn(0, 23)
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        if (start == end) return false
        return if (start < end) h in start until end else h >= start || h < end
    }
}
