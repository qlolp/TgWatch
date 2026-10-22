package ru.tgwatch

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal enum class BackupJobKind { EXPORT, EXPORT_LEGACY, READ_BACKUP, READ_CSV, APPLY_BACKUP, APPLY_CSV, RECOVER }
internal enum class BackupJobPhase { RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, EXPIRED }
internal data class BackupJobSnapshot<T>(val id: Long, val kind: BackupJobKind, val phase: BackupJobPhase,
    val cancellable: Boolean, val physicalRunning: Boolean, val value: T? = null)
internal data class BackupJobLimits(val timeoutMs: Long = 120_000, val previewTtlMs: Long = 300_000)

/** Process-wide logical state and physical admission; contains no Activity or persisted payload. */
internal class BackupJobCoordinator<T : Any>(private val io: Executor,
    private val schedule: (Long, () -> Unit) -> (() -> Unit), private val clock: () -> Long,
    private val changed: () -> Unit, private val limits: BackupJobLimits = BackupJobLimits(),
    private val cleanup: Executor = Executor { it.run() }) {
    private class Job<T>(val id: Long, val kind: BackupJobKind, val cancellable: Boolean, val deadline: Long?,
        val control: BackupJobControl) {
        var phase = BackupJobPhase.RUNNING
        var physicalRunning = true
        var value: T? = null
        var cancelTimer: (() -> Unit)? = null
        var cancelExpiry: (() -> Unit)? = null
        var expiresAt: Long? = null
        fun snapshot() = BackupJobSnapshot(id, kind, phase, cancellable, physicalRunning, value)
    }
    private var current: Job<T>? = null
    private var nextId = 0L
    init { require(limits.timeoutMs > 0 && limits.previewTtlMs > 0) }
    fun snapshot(): BackupJobSnapshot<T>? {
        reconcileDeadline()
        return synchronized(this) {
            expireIfDue(current)
            current?.snapshot()
        }
    }

    fun start(kind: BackupJobKind, cancellable: Boolean = true, previousId: Long? = null,
        sensitive: List<CharArray> = emptyList(),
        operation: (T?, BackupJobControl) -> T): Boolean {
        reconcileDeadline()
        val (job, previous) = synchronized(this) {
            val old = current
            expireIfDue(old)
            if (old?.physicalRunning == true || if (previousId == null) old != null
                else old?.id != previousId || old.phase != BackupJobPhase.SUCCEEDED) return false
            old?.cancelExpiry?.invoke()
            val due = if (cancellable) clock().let { it + minOf(limits.timeoutMs, Long.MAX_VALUE - it) } else null
            Job<T>(++nextId, kind, cancellable, due, BackupJobControl(clock, due, cleanup, sensitive))
                .also { current = it } to old?.value
        }
        return try {
            if (cancellable) {
                val cancel = schedule(limits.timeoutMs) { stop(job.id, BackupJobPhase.TIMED_OUT) }
                synchronized(this) {
                    if (current === job && job.physicalRunning) job.cancelTimer = cancel else cancel()
                }
            }
            io.execute { perform(job, previous, operation) }
            changed()
            true
        } catch (_: RejectedExecutionException) {
            job.cancelTimer?.invoke()
            synchronized(this) { if (current === job) current = null }
            job.control.finish()
            changed()
            false
        }
    }

    private fun perform(job: Job<T>, previous: T?, operation: (T?, BackupJobControl) -> T) {
        val result = runCatching {
            job.control.attach()
            operation(previous, job.control).also { job.control.check() }
        }
        job.control.finish()
        synchronized(this) {
            job.cancelTimer?.invoke()
            job.physicalRunning = false
            if (job.phase == BackupJobPhase.RUNNING) {
                job.phase = when (val error = result.exceptionOrNull()) {
                    null -> BackupJobPhase.SUCCEEDED
                    is BackupJobStopped -> error.phase
                    else -> BackupJobPhase.FAILED
                }
                job.value = result.getOrNull()
                if (job.phase == BackupJobPhase.SUCCEEDED &&
                    job.kind in setOf(BackupJobKind.READ_BACKUP, BackupJobKind.READ_CSV)) {
                    job.expiresAt = clock().let { it + minOf(limits.previewTtlMs, Long.MAX_VALUE - it) }
                }
            }
        }
        if (job.phase == BackupJobPhase.SUCCEEDED &&
            job.kind in setOf(BackupJobKind.READ_BACKUP, BackupJobKind.READ_CSV)) {
            try {
                val cancel = schedule(limits.previewTtlMs) { expire(job.id) }
                synchronized(this) { if (current === job) job.cancelExpiry = cancel else cancel() }
            } catch (_: RejectedExecutionException) { expire(job.id) }
        }
        changed()
    }

    fun cancel(id: Long): Boolean = stop(id, BackupJobPhase.CANCELLED)
    private fun reconcileDeadline() {
        val id = synchronized(this) {
            current?.takeIf { it.phase == BackupJobPhase.RUNNING && it.cancellable &&
                it.deadline?.let { due -> clock() >= due } == true }?.id
        }
        if (id != null) stop(id, BackupJobPhase.TIMED_OUT)
    }
    /** Called under the coordinator lock; elapsed time is authoritative even if a timer is delayed by sleep. */
    private fun expireIfDue(job: Job<T>?) {
        if (job?.phase == BackupJobPhase.SUCCEEDED && job.expiresAt?.let { clock() >= it } == true) {
            job.value = null
            job.phase = BackupJobPhase.EXPIRED
            job.cancelExpiry?.invoke()
        }
    }
    private fun stop(id: Long, reason: BackupJobPhase): Boolean {
        val control = synchronized(this) {
            val job = current?.takeIf {
                it.id == id && it.cancellable && it.phase == BackupJobPhase.RUNNING
            } ?: return false
            job.phase = reason
            job.value = null
            job.cancelTimer?.invoke()
            job.control
        }
        control.stop(reason)
        changed()
        return true
    }
    private fun expire(id: Long) {
        synchronized(this) {
            val job = current ?: return
            if (job.id != id || job.phase != BackupJobPhase.SUCCEEDED || job.physicalRunning) return
            job.value = null
            job.phase = BackupJobPhase.EXPIRED
        }
        changed()
    }
    fun dismiss(id: Long): Boolean {
        synchronized(this) {
            val job = current?.takeIf { it.id == id && !it.physicalRunning } ?: return false
            job.cancelExpiry?.invoke()
            current = null
        }
        changed()
        return true
    }
}
