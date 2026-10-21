package ru.tgwatch

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/** UI binder and bounded HTTPS GET for the egress ISO code. Never stores the address. */
internal object EgressCountryUi {
    private const val MAX_BYTES = 8 * 1024
    private const val TIMEOUT_MS = 3_000
    private const val COALESCE_MS = 30_000L
    private const val HTTP_OK = 200
    private const val HTTP_LAST_SUCCESS = 299

    @Volatile private var lastAttemptElapsed = 0L

    fun render(view: TextView, ctx: Context) {
        val snapshot = Prefs.egressCountry(ctx)
        if (snapshot == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = EgressCountry.label(snapshot.iso)
        view.contentDescription = view.text
    }

    fun refresh(ctx: Context, networkHandle: Long, open: (URL) -> HttpURLConnection) {
        val now = System.currentTimeMillis()
        val stored = Prefs.egressCountry(ctx)
        if (!EgressCountry.needsRefresh(stored, now, networkHandle)) return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (elapsed - lastAttemptElapsed < COALESCE_MS) return
        lastAttemptElapsed = elapsed
        Thread({
            val iso = fetch(open)
            if (iso != null) Prefs.setEgressCountry(ctx, EgressCountry.Snapshot(iso, now, networkHandle))
        }, "tg-egress").apply { isDaemon = true }.start()
    }

    fun refreshActive(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val network = cm?.activeNetwork ?: return
        refresh(ctx, network.networkHandle) { url ->
            network.openConnection(url) as HttpURLConnection
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any transport failure abandons the optional lookup.
    fun fetch(open: (URL) -> HttpURLConnection): String? = try {
        val connection = open(URL(EgressCountry.ENDPOINT))
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            readIso(connection)
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        // Silent informational lookup: keep the previous snapshot and never log response text.
        Log.w("TgWatch", "egress lookup failed: ${e.javaClass.simpleName}")
        null
    }

    private fun readIso(connection: HttpURLConnection): String? {
        val ok = connection.responseCode in HTTP_OK..HTTP_LAST_SUCCESS
        val bytes = if (!ok) null else connection.inputStream.use { input -> readBounded(input) }
        return bytes?.let { EgressCountry.parseTrace(it.toString(Charsets.UTF_8)) }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray? {
        val buffer = ByteArray(MAX_BYTES)
        var count = 0
        while (count < MAX_BYTES) {
            val read = input.read(buffer, count, MAX_BYTES - count)
            if (read < 0) break
            count += read
        }
        return if (count <= 0) null else buffer.copyOf(count)
    }
}
