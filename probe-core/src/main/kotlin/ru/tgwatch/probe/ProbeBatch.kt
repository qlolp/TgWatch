package ru.tgwatch.probe

import java.util.concurrent.*

/** Physical leases end only in the actual delegate's finally block, including after logical cancellation. */
class ProbeBatch(private val pool: ExecutorService, private val timeoutMs: Long = 6_000L,
    private val admission: ProbeAdmission = ProbeAdmission.process,
    private val lane: ProbeLane = ProbeLane.PRIMARY) {
    fun run(endpoints: List<ProbeEndpoint>,
        operation: (ProbeEndpoint, ProbeCancellation) -> ProbeOutcome): List<ProbeOutcome> =
        runMapped(endpoints, { ep, detail, measurement ->
            ProbeOutcome(ep.name, ep.group, false, -1L, detail, measurement)
        }, operation)

    /** Adapters may preserve their existing DTOs without copying the scheduling/protocol implementation. */
    // Probe transport failures are classified later; any Exception is a failed probe.
    @Suppress("TooGenericExceptionCaught")
    fun <R : Any> runMapped(endpoints: List<ProbeEndpoint>,
        fallback: (ProbeEndpoint, ProbeDetail, ProbeMeasurement) -> R,
        operation: (ProbeEndpoint, ProbeCancellation) -> R): List<R> {
        checkCoordinator()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val queue = ExecutorCompletionService<Pair<Int, R>>(pool)
        val handles = mutableListOf<Triple<Future<Pair<Int, R>>, ProbeCancellation, ProbePermit>>()
        val results = mutableListOf<Pair<Int, R>>()
        val started = ConcurrentHashMap.newKeySet<Int>()
        fun unavailable(endpoint: ProbeEndpoint, code: ProbeDetailCode) =
            fallback(endpoint, ProbeDetail(code), ProbeMeasurement.NOT_MEASURED)
        try {
            endpoints.forEachIndexed { index, endpoint ->
                val key = "${endpoint.group}:${endpoint.name}:${endpoint.address}"
                val lease = admission.tryAcquire(key, lane)
                if (lease == null) {
                    results += index to unavailable(endpoint, ProbeDetailCode.BUSY)
                    return@forEachIndexed
                }
                val cancellation = ProbeCancellation()
                try {
                    val future = queue.submit(Callable {
                        if (!lease.start()) {
                            return@Callable index to unavailable(endpoint, ProbeDetailCode.CANCELLED)
                        }
                        try {
                            cancellation.check()
                            started.add(index)
                            index to operation(endpoint, cancellation)
                        } catch (error: Exception) {
                            index to fallback(endpoint, ProbeDetail.failure(error), ProbeMeasurement.FAILURE)
                        } finally { cancellation.close(); lease.finished() }
                    })
                    handles += Triple(future, cancellation, lease)
                } catch (_: RejectedExecutionException) {
                    lease.cancelBeforeStart()
                    results += index to unavailable(endpoint, ProbeDetailCode.BUSY)
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
        return endpoints.mapIndexed { index, endpoint ->
            results.firstOrNull { it.first == index }?.second ?: fallback(endpoint,
                ProbeDetail(ProbeDetailCode.TIME_LIMIT),
                if (index in started) ProbeMeasurement.FAILURE else ProbeMeasurement.NOT_MEASURED)
        }
    }
    private fun checkCoordinator() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
    }
}
