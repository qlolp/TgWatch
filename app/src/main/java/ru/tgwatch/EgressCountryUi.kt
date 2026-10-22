package ru.tgwatch

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** UI binder and bounded HTTPS GET for the egress ISO code. Never stores the address. */
internal object EgressCountryUi {
    private const val MAX_BYTES = 8 * 1024
    private const val TIMEOUT_MS = 3_000
    private const val COALESCE_MS = 30_000L
    private const val HTTP_OK = 200
    private const val HTTP_LAST_SUCCESS = 299

    @Volatile private var lastAttemptElapsed = 0L
    @Volatile private var lastAttemptNetwork = -1L
    private val lookup = CountryLookupState()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "tg-egress").apply { isDaemon = true } })

    @Synchronized fun invalidate(ctx: Context) {
        lookup.invalidate()
        lastAttemptElapsed = 0L
        lastAttemptNetwork = -1L
        Prefs.clearEgressCountry(ctx)
    }

    fun render(view: TextView, ctx: Context) {
        val snapshot = Prefs.egressCountry(ctx)
        view.visibility = View.VISIBLE
        val network = activeHandle(ctx)
        val now = System.currentTimeMillis()
        view.text = when {
            network < 0 -> ctx.getString(R.string.country_no_network)
            lookup.checking(network) -> ctx.getString(R.string.country_checking)
            snapshot == null -> ctx.getString(R.string.country_unverified)
            else -> {
                val name = Locale("", snapshot.iso).getDisplayCountry(ctx.resources.configuration.locales[0])
                val current = !EgressCountry.needsRefresh(snapshot, now, network)
                ctx.getString(if (current) R.string.country_current else R.string.country_stale,
                    EgressCountry.flag(snapshot.iso), name, agoStr(ctx, snapshot.checkedAt, now))
            }
        }
        view.contentDescription = view.text
    }

    @Synchronized fun refresh(ctx: Context, networkHandle: Long, open: (URL) -> HttpURLConnection,
        force: Boolean = false) {
        val now = System.currentTimeMillis()
        val stored = Prefs.egressCountry(ctx)
        if (!force && !EgressCountry.needsRefresh(stored, now, networkHandle)) return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (!force && coalesced(networkHandle, elapsed)) return
        lastAttemptElapsed = elapsed
        lastAttemptNetwork = networkHandle
        val journalGeneration = RegionalRepository.generation(ctx)
        val ticket = lookup.begin(networkHandle, now)
        // A failed or interrupted forced refresh must not resurrect a confident cached country.
        Prefs.clearEgressCountry(ctx)
        try {
            executor.execute {
                val permit = ProbeAdmission.process.tryAcquire("country_lookup", ProbeLane.SECONDARY)
                val iso = if (permit?.start() == true) try { fetch(open) } finally { permit.finished() } else null
                lookup.finish(ticket, activeHandle(ctx), iso) {
                    Prefs.setEgressCountry(ctx, it)
                    RegionalJournalObserver.country(ctx, it, journalGeneration)
                }
            }
        } catch (_: RejectedExecutionException) {
            lookup.finish(ticket, activeHandle(ctx), null) { Prefs.setEgressCountry(ctx, it) }
        }
    }

    private fun coalesced(network: Long, elapsed: Long): Boolean = lastAttemptElapsed > 0 &&
        lastAttemptNetwork == network && elapsed - lastAttemptElapsed < COALESCE_MS

    fun refreshActive(ctx: Context, force: Boolean = false) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val network = cm?.activeNetwork ?: run { lookup.invalidate(); return }
        refresh(ctx, network.networkHandle, { url ->
            network.openConnection(url) as HttpURLConnection
        }, force)
    }

    private fun activeHandle(ctx: Context): Long =
        ctx.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork?.networkHandle ?: -1L

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
