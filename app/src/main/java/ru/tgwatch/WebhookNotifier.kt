package ru.tgwatch

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Opt-in outbound alert to a user-owned endpoint (typically ntfy.sh topic URL).
 * Off by default; never sends history or backup passwords — status line only.
 */
object WebhookNotifier {
    private const val TAG = "TgWatch"
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tg-webhook").apply { isDaemon = true }
    }

    fun maybeNotify(ctx: Context, status: MonitorService.Status, reason: String, latencyMs: Long) {
        if (!Prefs.webhookEnabled(ctx)) return
        val url = Prefs.webhookUrl(ctx).trim()
        if (!url.startsWith("https://") || url.length !in 12..256) return
        val app = ctx.applicationContext
        val title = when (status) {
            MonitorService.Status.OK -> Lang.t("Telegram доступен", "Telegram reachable")
            MonitorService.Status.PARTIAL -> Lang.t("Telegram частично", "Telegram partial")
            MonitorService.Status.TG_DOWN -> Lang.t("Telegram недоступен", "Telegram down")
            MonitorService.Status.NO_NETWORK -> Lang.t("Нет сети", "No network")
            MonitorService.Status.UNKNOWN -> Lang.t("Статус неизвестен", "Status unknown")
        }
        val body = buildString {
            append(title)
            if (latencyMs >= 0) append(" · ").append(latencyMs).append(if (Lang.ru) " мс" else " ms")
            if (reason.isNotBlank()) append('\n').append(reason.take(200))
        }
        io.execute {
            try {
                post(url, body)
            } catch (e: Exception) {
                Log.w(TAG, "webhook", e)
            }
        }
    }

    private fun post(url: String, body: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.setRequestProperty("User-Agent", "TgWatch/1.13")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
