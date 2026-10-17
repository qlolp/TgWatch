package ru.tgwatch.wear

import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import ru.tgwatch.probe.ProbeReport

data class WatchAttempt(val revision: Long, val rotation: Int, val deadlineElapsed: Long, val networkRevision: Long)
data class WatchProbeResult(val report: ProbeReport, val currentNetwork: Boolean = true)
enum class WatchAttemptState { IDLE, CHECKING, BUSY, UNMEASURED, CANCELLED, TIMEOUT, FAILED }

/** Logical deadline never releases the actual coordinator or core transport permits early. */
class WatchCheckController(private val clocks: WatchClocks, private val operation: (WatchAttempt) -> WatchProbeResult,
    private val published: (WatchSnapshot) -> Unit, private val execution: WatchExecution = WatchExecution.process,
    initial: WatchSnapshot? = null, private val changed: () -> Unit = {}) {
    private class Active(val value: WatchAttempt) {
        lateinit var future: FutureTask<Unit>
        val timeout = AtomicReference<(() -> Unit)?>(null)
    }
    private var active: Active? = null
    @Volatile private var last = initial
    @Volatile var attemptState = WatchAttemptState.IDLE
        private set
    val isChecking: Boolean get() = synchronized(this) { active != null }
    fun snapshot(): WatchSnapshot? = last

    fun request(): Boolean {
        val token = synchronized(this) {
            if (active != null) return false
            val started = clocks.elapsed().coerceAtLeast(0L)
            val revision = execution.nextRevision()
            Active(WatchAttempt(revision, (revision % Int.MAX_VALUE).toInt(),
                started + minOf(DEADLINE_MS, Long.MAX_VALUE - started), clocks.networkRevision())).also { token ->
                token.future = FutureTask<Unit> { perform(token) }
                active = token
                attemptState = WatchAttemptState.CHECKING
            }
        }
        return try {
            val cancel = execution.scheduler.schedule((token.value.deadlineElapsed - clocks.elapsed()).coerceAtLeast(0)) {
                finishWithoutResult(token, WatchAttemptState.TIMEOUT)
            }
            token.timeout.set(cancel)
            if (!synchronized(this) { active === token }) token.timeout.getAndSet(null)?.invoke()
            execution.coordinator.execute(token.future)
            changed()
            true
        } catch (_: RejectedExecutionException) {
            finishWithoutResult(token, WatchAttemptState.BUSY)
            false
        }
    }
    fun cancel() {
        val token = synchronized(this) { active } ?: return
        finishWithoutResult(token, WatchAttemptState.CANCELLED)
    }
    private fun finishWithoutResult(token: Active, reason: WatchAttemptState) {
        val current = synchronized(this) {
            if (active !== token) false else { active = null; attemptState = reason; true }
        }
        if (current) {
            token.timeout.getAndSet(null)?.invoke()
            token.future.cancel(true)
            changed()
        }
    }
    private fun perform(token: Active) {
        val result = runCatching { operation(token.value) }.getOrNull()
        val completed = clocks.elapsed().coerceAtLeast(0)
        val at = clocks.wall().coerceAtLeast(0)
        val next = synchronized(this) {
            if (active !== token) return
            active = null
            val expired = completed >= token.value.deadlineElapsed
            val current = token.value.networkRevision == clocks.networkRevision() && result?.currentNetwork == true
            val report = result?.report
            when {
                expired -> { attemptState = WatchAttemptState.TIMEOUT; null }
                !current || report == null -> { attemptState = WatchAttemptState.FAILED; null }
                !report.publishable -> { attemptState = WatchAttemptState.UNMEASURED; null }
                else -> {
                    attemptState = WatchAttemptState.IDLE
                    val status = report.status.takeIf { it in WatchSnapshot.STATUSES } ?: "UNKNOWN"
                    WatchSnapshot(status, at, completed, report.latencyMs.takeIf { it >= 0 },
                        token.value.revision, clocks.boot()).also { last = it }
                }
            }
        }
        token.timeout.getAndSet(null)?.invoke()
        next?.let(published)
        changed()
    }
    private companion object { const val DEADLINE_MS = 8_000L }
}
