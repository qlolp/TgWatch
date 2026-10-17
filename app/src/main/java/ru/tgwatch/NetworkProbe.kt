package ru.tgwatch

import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.*
import javax.net.ssl.SSLException

/** EXTRA results are informational only and never take part in Telegram classification. */
enum class ProbeGroup { WEB, MTPROTO, CONTROL, EXTRA }
enum class ProbeMeasurement { SUCCESS, FAILURE, NOT_MEASURED }
data class ProbeEndpoint(val name: String, val group: ProbeGroup, val address: String)
data class ProbeOutcome(val endpoint: String, val group: ProbeGroup, val reachable: Boolean,
    val latencyMs: Long = -1, val detail: String,
    val measurement: ProbeMeasurement = if (reachable) ProbeMeasurement.SUCCESS else ProbeMeasurement.FAILURE)

data class ProbeReport(val results: List<ProbeOutcome>, val connected: Boolean = true) {
    val publishable: Boolean get() = !connected || hasPositiveEvidence(results) ||
        hasCompleteMeasurements(results)
    val status get() = classify(results, connected)
    val latencyMs get() = results.filter { it.reachable && it.group == ProbeGroup.MTPROTO }
        .minOfOrNull { it.latencyMs } ?: -1L
    val reason get() = when (status) {
        "OK" -> Lang.t("MTProto и веб-ресурсы отвечают; доставка сообщений не проверяется", "MTProto and web resources are responding; message delivery is not checked")
        "PARTIAL" -> if (results.any { it.group == ProbeGroup.MTPROTO && it.reachable })
            Lang.t("MTProto отвечает, веб-ресурсы не подтвердили доступность", "MTProto is responding; web resources did not confirm reachability") else
            Lang.t("Веб-ресурсы отвечают, MTProto не подтвердил доступность", "Web resources are responding; MTProto did not confirm reachability")
        "TG_DOWN" -> Lang.t("Контрольные сайты отвечают, Telegram не отвечает", "Control sites are responding; Telegram is not")
        "NO_NETWORK" -> Lang.t("Нет активной сети или требуется вход в Wi-Fi", "No active network, or Wi-Fi sign-in is required")
        else -> Lang.t("Доступность не определена: ни один адрес не подтвердил связь", "Reachability unknown: no address confirmed a connection")
    }
    companion object {
        private val PRIMARY_GROUPS = setOf(ProbeGroup.WEB, ProbeGroup.MTPROTO)
        private fun hasPositiveEvidence(results: List<ProbeOutcome>) =
            results.any { it.group in PRIMARY_GROUPS && it.reachable }
        private fun hasCompleteMeasurements(results: List<ProbeOutcome>) =
            results.any { it.group in PRIMARY_GROUPS } &&
                results.none { it.group in PRIMARY_GROUPS && it.measurement == ProbeMeasurement.NOT_MEASURED }
        fun classify(results: List<ProbeOutcome>, connected: Boolean): String {
            if (!connected) return "NO_NETWORK"
            val web = results.any { it.group == ProbeGroup.WEB && it.reachable }
            val mt = results.any { it.group == ProbeGroup.MTPROTO && it.reachable }
            return when {
                web && mt -> "OK"
                web || mt -> "PARTIAL"
                !hasCompleteMeasurements(results) -> "UNKNOWN"
                results.any { it.group == ProbeGroup.CONTROL && it.reachable } -> "TG_DOWN"
                else -> "UNKNOWN"
            }
        }
    }
}

/** Cancellation closes active sockets/connections; no unbounded executor queue. */
class ProbeCancellation : Closeable {
    private var cancelled = false
    private val actions = mutableListOf<() -> Unit>()
    fun register(action: () -> Unit) {
        val closeNow = synchronized(this) { if (cancelled) true else { actions.add(action); false } }
        if (closeNow) clean(action)
    }
    @Synchronized fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
    }
    override fun close() {
        val copy = synchronized(this) {
            cancelled = true
            actions.toList().also { actions.clear() }
        }
        copy.forEach(::clean)
    }
    companion object {
        // A broken platform disconnect must not block the batch coordinator or grow a queue.
        private val cleanup = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { r -> Thread(r, "tg-probe-close").apply { isDaemon = true } })
        private fun clean(action: () -> Unit) {
            try { cleanup.execute { try { action() } catch (_: Exception) {} } }
            catch (_: RejectedExecutionException) { /* Worker finally/transport timeout still owns cleanup. */ }
        }
    }
}

class ProbeBatch(private val pool: ExecutorService, private val timeoutMs: Long = 6_000L) {
    private class Lease(private val release: () -> Unit) {
        private val state = java.util.concurrent.atomic.AtomicInteger(0)
        fun start() = state.compareAndSet(0, 1)
        fun cancelBeforeStart() { if (state.compareAndSet(0, 2)) release() }
        fun finish() { if (state.compareAndSet(1, 2)) release() }
    }
    companion object {
        private val occupied = java.util.WeakHashMap<ExecutorService, MutableSet<String>>()
        private fun acquire(pool: ExecutorService, endpoint: ProbeEndpoint): Lease? = synchronized(occupied) {
            val key = "${endpoint.group}:${endpoint.name}:${endpoint.address}"
            val active = occupied.getOrPut(pool) { mutableSetOf() }
            if (!active.add(key)) null else Lease { synchronized(occupied) { active.remove(key) } }
        }
    }
    fun run(endpoints: List<ProbeEndpoint>, operation: (ProbeEndpoint, ProbeCancellation) -> ProbeOutcome): List<ProbeOutcome> {
        checkCoordinator()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val queue = ExecutorCompletionService<ProbeOutcome>(pool)
        val handles = mutableListOf<Triple<Future<ProbeOutcome>, ProbeCancellation, Lease>>()
        val results = mutableListOf<ProbeOutcome>()
        val started = ConcurrentHashMap.newKeySet<String>()
        try {
            endpoints.forEach { endpoint ->
                val lease = acquire(pool, endpoint)
                if (lease == null) {
                    results += notMeasured(endpoint, Lang.t("Предыдущий запрос ещё завершается", "The previous request is still finishing"))
                    return@forEach
                }
                val cancellation = ProbeCancellation()
                try {
                    val future = queue.submit(Callable {
                        if (!lease.start()) return@Callable notMeasured(endpoint, Lang.t("Проверка отменена", "Check cancelled"))
                        try {
                            cancellation.check()
                            started.add(endpoint.name)
                            operation(endpoint, cancellation)
                        }
                        catch (e: Exception) { ProbeOutcome(endpoint.name, endpoint.group, false, -1, describeProbeError(e)) }
                        finally { cancellation.close(); lease.finish() }
                    })
                    handles += Triple(future, cancellation, lease)
                } catch (_: RejectedExecutionException) {
                    lease.cancelBeforeStart()
                    results += notMeasured(endpoint, Lang.t("Предыдущий запрос ещё завершается", "The previous request is still finishing"))
                }
            }
            repeat(handles.size) {
                val left = deadline - System.nanoTime()
                if (left <= 0L) return@repeat
                val done = queue.poll(left, TimeUnit.NANOSECONDS) ?: return@repeat
                results += done.get()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally {
            handles.forEach { (future, cancellation, lease) ->
                future.cancel(true); lease.cancelBeforeStart(); cancellation.close()
            }
        }
        checkCoordinator()
        return endpoints.map { ep -> results.firstOrNull { it.endpoint == ep.name }
            ?: ProbeOutcome(ep.name, ep.group, false, -1, Lang.t("Общий срок ожидания истёк", "Overall time limit expired"),
                if (ep.name in started) ProbeMeasurement.FAILURE else ProbeMeasurement.NOT_MEASURED) }
    }

    private fun notMeasured(endpoint: ProbeEndpoint, detail: String) =
        ProbeOutcome(endpoint.name, endpoint.group, false, -1, detail, ProbeMeasurement.NOT_MEASURED)

    private fun checkCoordinator() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
    }
}

fun describeProbeError(e: Exception): String = when (e) {
    is UnknownHostException -> Lang.t("DNS: адрес не найден", "DNS: address not found")
    is SSLException -> Lang.t("TLS: защищённое соединение не установлено", "TLS: secure connection not established")
    is SocketTimeoutException, is TimeoutException -> Lang.t("Таймаут", "Timeout")
    is InterruptedException -> Lang.t("Проверка отменена", "Check cancelled")
    else -> "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
}

/**
 * Separate network adapter allows Android to bind all requests to one Network.
 * The caller owns and reuses both distinct executors, shutting them down when monitoring stops.
 */
class NetworkProbe(
    private val pool: ExecutorService,
    private val extraPool: ExecutorService,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val openSocket: () -> java.net.Socket = { java.net.Socket() },
) {
    init { require(pool !== extraPool) { "EXTRA probes require a separate executor" } }

    /** Compatibility API: waits for both batches. Services publish [checkPrimary] before requesting extras. */
    fun check(extras: List<ProbeEndpoint> = emptyList()): ProbeReport {
        val primary = checkPrimary()
        return primary.copy(results = primary.results + checkExtras(extras))
    }

    fun checkExtras(extras: List<ProbeEndpoint>): List<ProbeOutcome> =
        ProbeBatch(extraPool, ExtraProbes.BATCH_TIMEOUT_MS).run(extras.filter { it.group == ProbeGroup.EXTRA }, ::probe)

    fun checkPrimary(): ProbeReport {
        val telegram = listOf(
            ProbeEndpoint("api.telegram.org", ProbeGroup.WEB, "https://api.telegram.org/"),
            ProbeEndpoint("web.telegram.org", ProbeGroup.WEB, "https://web.telegram.org/"),
            ProbeEndpoint("core.telegram.org", ProbeGroup.WEB, "https://core.telegram.org/"),
        ) + MtProtoEndpoints.all
        val results = ProbeBatch(pool).run(telegram, ::probe).toMutableList()
        // Controls are unnecessary when Telegram itself proves internet connectivity.
        if (results.none { it.reachable }) {
            val controls = listOf(
                ProbeEndpoint(Lang.t("Яндекс", "Yandex"), ProbeGroup.CONTROL, "https://ya.ru/"),
                ProbeEndpoint("Mail.ru", ProbeGroup.CONTROL, "https://mail.ru/"),
                ProbeEndpoint("Google 204", ProbeGroup.CONTROL, "https://www.gstatic.com/generate_204"),
            )
            results += ProbeBatch(pool).run(controls, ::probe)
        }
        return ProbeReport(results)
    }
    private fun probe(ep: ProbeEndpoint, cancellation: ProbeCancellation): ProbeOutcome {
        val start = System.nanoTime()
        if (ep.group == ProbeGroup.MTPROTO) {
            val ok = MtProto.probe(ep.address, openSocket, cancellation)
            return ProbeOutcome(ep.name, ep.group, ok, elapsed(start), if (ok) Lang.t("MTProto resPQ; nonce совпал", "MTProto resPQ; nonce matched") else Lang.t("Ответ MTProto не подтверждён", "MTProto response not confirmed"))
        }
        fun request(method: String): Int {
            cancellation.check()
            val connection = openConnection(URL(ep.address))
            cancellation.register { connection.disconnect() }
            return try {
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "TgWatch/${BuildConfig.VERSION_NAME} (Android)")
                connection.responseCode
            } finally { connection.disconnect() }
        }
        var code = request("HEAD")
        if (code == 405 || code == 501) code = request("GET")
        return ProbeOutcome(ep.name, ep.group, ProbeRules.isReachableHttpCode(code), elapsed(start), "HTTP $code")
    }
    private fun elapsed(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    companion object {
        // Admit all 13 Telegram endpoints; retain room for 3 controls if cancellation is delayed.
        private const val PRIMARY_WORKERS = 16
        private const val EXTRA_WORKERS = 3
        fun executor(): ExecutorService = ThreadPoolExecutor(PRIMARY_WORKERS, PRIMARY_WORKERS, 30L, TimeUnit.SECONDS,
            SynchronousQueue(), { r -> Thread(r, "tg-probe").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        fun extraExecutor(): ExecutorService = ThreadPoolExecutor(EXTRA_WORKERS, EXTRA_WORKERS, 30L, TimeUnit.SECONDS,
            SynchronousQueue(), { r -> Thread(r, "tg-extra-probe").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
