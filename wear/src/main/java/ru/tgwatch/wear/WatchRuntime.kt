package ru.tgwatch.wear

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import ru.tgwatch.probe.ProbeReport
import ru.tgwatch.probe.ProbeTransport

class WatchApplication : Application() {
    override fun onCreate() { super.onCreate(); WatchRuntime.initialize(this) }
}

/** Passive cache and network observer. Only a user's controller request opens probe transports. */
internal object WatchRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val io = ThreadPoolExecutor(1, 1, IO_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "tg-watch-cache").apply { isDaemon = true } })
    private val observers = mutableListOf<WeakReference<Observer>>()
    private val networkRevision = AtomicLong()
    private var network: Network? = null
    private var captive = false
    private lateinit var cm: ConnectivityManager
    private lateinit var cmContext: Context
    private lateinit var store: WatchStore
    private var pending: WatchSnapshot? = null
    private var saving = false
    @Volatile var ready = false
        private set
    @Volatile var boot = 0L
        private set
    @Volatile var snapshot: WatchSnapshot? = null
        private set
    interface Observer { fun onWatchChanged() }

    fun initialize(context: Context) {
        cmContext = context.applicationContext
        cm = context.getSystemService(ConnectivityManager::class.java)
        store = WatchStore(context)
        network = cm.activeNetwork
        captive = isCaptive(cm.getNetworkCapabilities(network))
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) = updateNetwork(value,
                isCaptive(cm.getNetworkCapabilities(value)))
            override fun onLost(value: Network) { synchronized(this@WatchRuntime) {
                if (network == value) updateNetwork(null, false)
            } }
            override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) {
                if (value == cm.activeNetwork) updateNetwork(value, isCaptive(caps))
            }
        })
        io.execute {
            runCatching { WatchSurfaceAction.initialize(context) }
            boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong() }
                .getOrElse { (SecureRandom().nextLong() and Long.MAX_VALUE) or SYNTHETIC_BOOT_FLAG }
            val restored = store.load()
            snapshot = restored?.let { if (it.bootSession == boot) it else it.copy(checkedElapsedMs = 0) }
            WatchExecution.process.seedRevision(restored?.revision ?: 0)
            ready = true
            notifyChanged()
        }
    }
    private fun isCaptive(caps: NetworkCapabilities?): Boolean =
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
    @Synchronized private fun updateNetwork(value: Network?, isCaptive: Boolean) {
        if (network != value || captive != isCaptive) {
            network = value
            captive = isCaptive
            networkRevision.incrementAndGet()
        }
    }
    fun observe(observer: Observer) = synchronized(observers) {
        observers.removeAll { it.get() == null || it.get() === observer }
        observers.add(WeakReference(observer))
    }
    fun unobserve(observer: Observer) = synchronized(observers) {
        observers.removeAll { it.get() == null || it.get() === observer }
    }
    private fun notifyChanged() { main.post {
        val current = synchronized(observers) { observers.mapNotNull { it.get() } }
        current.forEach { it.onWatchChanged() }
    } }
    fun controller(changed: () -> Unit): WatchCheckController = WatchCheckController(
        WatchClocks(System::currentTimeMillis, SystemClock::elapsedRealtime, { boot }, networkRevision::get),
        operation = { attempt ->
            val bound = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(bound)
            val report = when {
                bound == null -> ProbeReport(emptyList(), connected = false)
                caps == null -> ProbeReport(emptyList())
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ->
                    ProbeReport(emptyList(), connected = false)
                else -> WatchProbeRunner(object : ProbeTransport {
                    override val userAgent = "TgWatch/${BuildConfig.VERSION_NAME} (Wear OS)"
                    override fun openConnection(url: URL): HttpURLConnection =
                        bound.openConnection(url) as HttpURLConnection
                    override fun openSocket(): Socket = bound.socketFactory.createSocket()
                }).check(attempt.rotation, (attempt.deadlineElapsed - SystemClock.elapsedRealtime() - PROBE_MARGIN_MS)
                    .coerceIn(1, WatchProbePlan.NETWORK_BUDGET_MS))
            }
            WatchProbeResult(report, currentNetwork = cm.activeNetwork == bound)
        }, published = ::publish, initial = snapshot, changed = changed)

    private fun publish(value: WatchSnapshot) {
        snapshot = value
        notifyChanged()
        runCatching {
            androidx.wear.tiles.TileService.getUpdater(cmContext).requestUpdate(StatusTileService::class.java)
        }
        runCatching {
            val component = android.content.ComponentName(cmContext, StatusComplicationService::class.java)
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
                .create(cmContext, component).requestUpdateAll()
        }
        synchronized(this) {
            pending = value
            if (saving) return
            saving = true
        }
        io.execute {
            while (true) {
                val next = synchronized(this) {
                    pending.also { pending = null; if (it == null) saving = false }
                } ?: break
                runCatching { store.save(next) }
            }
        }
    }

    private const val IO_KEEP_ALIVE_SECONDS = 30L
    private const val SYNTHETIC_BOOT_FLAG = 1L shl 32
    private const val PROBE_MARGIN_MS = 100
}
