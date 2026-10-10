package ru.tgwatch

import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.*
import javax.net.ssl.SSLException

enum class ProbeGroup { WEB, MTPROTO, CONTROL }
data class ProbeEndpoint(val name: String, val group: ProbeGroup, val address: String)
data class ProbeOutcome(val endpoint: String, val group: ProbeGroup, val reachable: Boolean,
    val latencyMs: Long = -1, val detail: String)

data class ProbeReport(val results: List<ProbeOutcome>, val connected: Boolean = true) {
    val status get() = classify(results, connected)
    val latencyMs get() = results.filter { it.reachable && it.group == ProbeGroup.MTPROTO }
        .minOfOrNull { it.latencyMs } ?: -1L
    val reason get() = when (status) {
        "OK" -> "MTProto и веб-ресурсы отвечают; доставка сообщений не проверяется"
        "PARTIAL" -> if (results.any { it.group == ProbeGroup.MTPROTO && it.reachable })
            "MTProto отвечает, веб-ресурсы не подтвердили доступность" else
            "Веб-ресурсы отвечают, MTProto не подтвердил доступность"
        "TG_DOWN" -> "Контрольные сайты отвечают, Telegram не отвечает"
        "NO_NETWORK" -> "Нет активной сети или требуется вход в Wi-Fi"
        else -> "Доступность не определена: ни один адрес не подтвердил связь"
    }
    companion object {
        fun classify(results: List<ProbeOutcome>, connected: Boolean): String {
            if (!connected) return "NO_NETWORK"
            val web = results.any { it.group == ProbeGroup.WEB && it.reachable }
            val mt = results.any { it.group == ProbeGroup.MTPROTO && it.reachable }
            return when {
                web && mt -> "OK"
                web || mt -> "PARTIAL"
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
            if (!active.add(key)) null else Lease { synchronized(occupied) { active.remove(key); Unit } }
        }
    }
    fun run(endpoints: List<ProbeEndpoint>, operation: (ProbeEndpoint, ProbeCancellation) -> ProbeOutcome): List<ProbeOutcome> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val queue = ExecutorCompletionService<ProbeOutcome>(pool)
        val handles = mutableListOf<Triple<Future<ProbeOutcome>, ProbeCancellation, Lease>>()
        val results = mutableListOf<ProbeOutcome>()
        try {
            endpoints.forEach { endpoint ->
                val lease = acquire(pool, endpoint)
                if (lease == null) {
                    results += ProbeOutcome(endpoint.name, endpoint.group, false, -1, "Предыдущий запрос ещё завершается")
                    return@forEach
                }
                val cancellation = ProbeCancellation()
                try {
                    val future = queue.submit(Callable {
                        if (!lease.start()) return@Callable ProbeOutcome(endpoint.name, endpoint.group, false, -1, "Проверка отменена")
                        try { cancellation.check(); operation(endpoint, cancellation) }
                        catch (e: Exception) { ProbeOutcome(endpoint.name, endpoint.group, false, -1, describeProbeError(e)) }
                        finally { cancellation.close(); lease.finish() }
                    })
                    handles += Triple(future, cancellation, lease)
                } catch (_: RejectedExecutionException) {
                    lease.cancelBeforeStart()
                    results += ProbeOutcome(endpoint.name, endpoint.group, false, -1, "Предыдущий запрос ещё завершается")
                }
            }
            repeat(handles.size) {
                val left = deadline - System.nanoTime()
                if (left <= 0L) return@repeat
                val done = queue.poll(left, TimeUnit.NANOSECONDS) ?: return@repeat
                results += done.get()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            handles.forEach { (future, cancellation, lease) ->
                future.cancel(true); lease.cancelBeforeStart(); cancellation.close()
            }
        }
        return endpoints.map { ep -> results.firstOrNull { it.endpoint == ep.name }
            ?: ProbeOutcome(ep.name, ep.group, false, -1, "Общий срок ожидания истёк") }
    }
}

fun describeProbeError(e: Exception): String = when (e) {
    is UnknownHostException -> "DNS: адрес не найден"
    is SSLException -> "TLS: защищённое соединение не установлено"
    is SocketTimeoutException, is TimeoutException -> "Таймаут"
    is InterruptedException -> "Проверка отменена"
    else -> "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
}

/** Separate network adapter allows Android to bind all requests to one Network. */
class NetworkProbe(
    private val pool: ExecutorService,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val openSocket: () -> java.net.Socket = { java.net.Socket() },
) {
    fun check(): ProbeReport {
        val telegram = listOf(
            ProbeEndpoint("api.telegram.org", ProbeGroup.WEB, "https://api.telegram.org/"),
            ProbeEndpoint("web.telegram.org", ProbeGroup.WEB, "https://web.telegram.org/"),
            ProbeEndpoint("core.telegram.org", ProbeGroup.WEB, "https://core.telegram.org/"),
            ProbeEndpoint("MTProto DC2", ProbeGroup.MTPROTO, "149.154.167.51"),
            ProbeEndpoint("MTProto DC4", ProbeGroup.MTPROTO, "149.154.167.91"),
        )
        val results = ProbeBatch(pool).run(telegram, ::probe).toMutableList()
        // Controls are unnecessary when Telegram itself proves internet connectivity.
        if (results.none { it.reachable }) {
            val controls = listOf(
                ProbeEndpoint("Яндекс", ProbeGroup.CONTROL, "https://ya.ru/"),
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
            return ProbeOutcome(ep.name, ep.group, ok, elapsed(start), if (ok) "MTProto resPQ; nonce совпал" else "Ответ MTProto не подтверждён")
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
                connection.setRequestProperty("User-Agent", "TgWatch/1.7 (Android)")
                connection.responseCode
            } finally { connection.disconnect() }
        }
        var code = request("HEAD")
        if (code == 405 || code == 501) code = request("GET")
        return ProbeOutcome(ep.name, ep.group, ProbeRules.isReachableHttpCode(code), elapsed(start), "HTTP $code")
    }
    private fun elapsed(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    companion object {
        fun executor(): ExecutorService = ThreadPoolExecutor(8, 8, 30L, TimeUnit.SECONDS,
            SynchronousQueue(), { r -> Thread(r, "tg-probe").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
