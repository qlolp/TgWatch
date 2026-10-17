package ru.tgwatch.probe

import java.util.concurrent.*

/** Physical leases end only in the actual delegate's finally block, including after logical cancellation. */
class ProbeBatch(private val pool: ExecutorService, private val timeoutMs: Long = 6_000L,
    private val admission: ProbeAdmission = ProbeAdmission.process,
    private val lane: ProbeLane = ProbeLane.PRIMARY) {
    fun run(endpoints: List<ProbeEndpoint>, operation: (ProbeEndpoint, ProbeCancellation) -> ProbeOutcome): List<ProbeOutcome> =
        runMapped(endpoints, { ep, detail, measurement ->
            ProbeOutcome(ep.name, ep.group, false, -1L, detail, measurement)
        }, operation)

    /** Adapters may preserve their existing DTOs without copying the scheduling/protocol implementation. */
    fun <R : Any> runMapped(endpoints: List<ProbeEndpoint>,
        fallback: (ProbeEndpoint, ProbeDetail, ProbeMeasurement) -> R,
        operation: (ProbeEndpoint, ProbeCancellation) -> R): List<R> {
        checkCoordinator()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val queue = ExecutorCompletionService<Pair<String, R>>(pool)
        val handles = mutableListOf<Triple<Future<Pair<String, R>>, ProbeCancellation, ProbePermit>>()
        val results = mutableListOf<Pair<String, R>>()
        val started = ConcurrentHashMap.newKeySet<String>()
        fun unavailable(endpoint: ProbeEndpoint, code: ProbeDetailCode) =
            fallback(endpoint, ProbeDetail(code), ProbeMeasurement.NOT_MEASURED)
        try {
            endpoints.forEach { endpoint ->
                val key = "${endpoint.group}:${endpoint.name}:${endpoint.address}"
                val lease = admission.tryAcquire(key, lane)
                if (lease == null) {
                    results += endpoint.name to unavailable(endpoint, ProbeDetailCode.BUSY)
                    return@forEach
                }
                val cancellation = ProbeCancellation()
                try {
                    val future = queue.submit(Callable {
                        if (!lease.start()) return@Callable endpoint.name to unavailable(endpoint, ProbeDetailCode.CANCELLED)
                        try {
                            cancellation.check()
                            started.add(endpoint.name)
                            endpoint.name to operation(endpoint, cancellation)
                        } catch (error: Exception) {
                            endpoint.name to fallback(endpoint, ProbeDetail.failure(error), ProbeMeasurement.FAILURE)
                        } finally { cancellation.close(); lease.finished() }
                    })
                    handles += Triple(future, cancellation, lease)
                } catch (_: RejectedExecutionException) {
                    lease.cancelBeforeStart()
                    results += endpoint.name to unavailable(endpoint, ProbeDetailCode.BUSY)
                }
            }
            repeat(handles.size) {
                val left = deadline - System.nanoTime()
                if (left <= 0L) return@repeat
                val done = queue.poll(left, TimeUnit.NANOSECONDS) ?: return@repeat
                results += done.get()
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } finally {
            handles.forEach { (future, cancellation, lease) ->
                future.cancel(true); lease.cancelBeforeStart(); cancellation.close()
            }
        }
        checkCoordinator()
        return endpoints.map { endpoint -> results.firstOrNull { it.first == endpoint.name }?.second ?: fallback(endpoint,
            ProbeDetail(ProbeDetailCode.TIME_LIMIT),
            if (endpoint.name in started) ProbeMeasurement.FAILURE else ProbeMeasurement.NOT_MEASURED) }
    }
    private fun checkCoordinator() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
    }
}
