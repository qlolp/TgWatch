package ru.tgwatch

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** Bounded HTTPS GET for regional presets; never follows redirects or stores bodies. */
internal object RegionalServicesProbe {
    private const val TIMEOUT_MS = 3_000
    private const val COALESCE_MS = 5 * 60_000L
    private const val TAG = "TgWatch"

    @Volatile private var lastAttemptElapsed = 0L
    @Volatile private var lastNetworkHandle = 0L

    fun refresh(ctx: Context, networkHandle: Long, open: (URL) -> HttpURLConnection) {
        val enabled = RegionalServices.all.filter { Prefs.regionalEnabled(ctx, it.id) }
        if (enabled.isEmpty()) {
            Prefs.clearRegionalOutcomes(ctx)
            return
        }
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val networkChanged = lastNetworkHandle != 0L && lastNetworkHandle != networkHandle
        if (!networkChanged && elapsed - lastAttemptElapsed < COALESCE_MS) return
        lastAttemptElapsed = elapsed
        lastNetworkHandle = networkHandle
        Thread({
            val previous = enabled.associate { it.id to Prefs.regionalOutcome(ctx, it.id) }
            val results = linkedMapOf<RegionalServices.Id, GeoBlockPolicy.Outcome>()
            for (service in enabled) {
                results[service.id] = probe(service.url, open)
            }
            Prefs.setRegionalOutcomes(ctx, results, System.currentTimeMillis())
            logTransitions(ctx, previous, results)
            broadcastState(ctx)
        }, "tg-regional").apply { isDaemon = true }.start()
    }

    @Suppress("TooGenericExceptionCaught") // Broadcast delivery failures must not abort the probe thread.
    private fun broadcastState(ctx: Context) {
        try {
            ctx.sendBroadcast(android.content.Intent(MonitorService.ACTION_STATE_CHANGED)
                .setPackage(ctx.packageName))
        } catch (e: Exception) {
            Log.w(TAG, "regional broadcast: ${e.javaClass.simpleName}")
        }
    }

    fun refreshActive(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val network = cm?.activeNetwork ?: return
        refresh(ctx, network.networkHandle) { url ->
            network.openConnection(url) as HttpURLConnection
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any transport failure is a non-geo DOWN outcome.
    fun probe(url: String, open: (URL) -> HttpURLConnection): GeoBlockPolicy.Outcome = try {
        val connection = open(URL(url))
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            val body = bodyForClassification(connection, code)
            GeoBlockPolicy.classify(code, location, body)
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "regional probe failed: ${e.javaClass.simpleName}")
        GeoBlockPolicy.Outcome.DOWN
    }

    private fun bodyForClassification(connection: HttpURLConnection, code: Int): String? =
        if (code in HTTP_OK..HTTP_LAST_SUCCESS) readBody(connection) else null

    private fun readBody(connection: HttpURLConnection): String? {
        val stream = connection.inputStream ?: return null
        val buffer = ByteArray(GeoBlockPolicy.MAX_BODY_BYTES)
        var count = 0
        stream.use { input ->
            while (count < GeoBlockPolicy.MAX_BODY_BYTES) {
                val read = input.read(buffer, count, GeoBlockPolicy.MAX_BODY_BYTES - count)
                if (read < 0) return@use
                count += read
            }
        }
        return if (count <= 0) null else buffer.copyOf(count).toString(Charsets.UTF_8)
    }

    private fun logTransitions(ctx: Context,
        previous: Map<RegionalServices.Id, GeoBlockPolicy.Outcome?>,
        current: Map<RegionalServices.Id, GeoBlockPolicy.Outcome>) {
        current.entries
            .filter { (id, outcome) ->
                outcome == GeoBlockPolicy.Outcome.GEO_BLOCK &&
                    previous[id] != GeoBlockPolicy.Outcome.GEO_BLOCK
            }
            .forEach { (id, _) ->
                val label = ctx.getString(RegionalServices.service(id).labelRes)
                EventLog.add(ctx, ctx.getString(R.string.regional_event_geo_block, label))
            }
    }

    private const val HTTP_OK = 200
    private const val HTTP_LAST_SUCCESS = 299
}
