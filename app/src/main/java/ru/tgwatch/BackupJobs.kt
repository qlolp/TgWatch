package ru.tgwatch

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal sealed interface BackupJobResult {
    data object Saved : BackupJobResult
    data object Applied : BackupJobResult
    data class Import(val data: TargetBackupImport) : BackupJobResult
    data class Csv(val rows: Map<TargetId, List<TargetObservation>>, val legacy: Boolean) : BackupJobResult
}

/** Only application context and bounded in-memory results outlive the screen. Nothing is persisted here. */
internal object BackupJobs {
    private const val IDLE_SECONDS = 30L
    interface Observer { fun onBackupChanged() }
    private val main = Handler(Looper.getMainLooper())
    private var observer = WeakReference<Observer>(null)
    private val io = ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "tg-backup").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val cleanup = ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "tg-backup-cancel").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "tg-backup-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    internal val coordinator = BackupJobCoordinator<BackupJobResult>(io, { delay, task ->
        val future = timer.schedule(task, delay, TimeUnit.MILLISECONDS)
        val cancel: () -> Unit = { future.cancel(false) }
        cancel
    }, SystemClock::elapsedRealtime, { main.post { observer.get()?.onBackupChanged() } }, cleanup = cleanup)

    fun attach(value: Observer) { observer = WeakReference(value); main.post { observer.get()?.onBackupChanged() } }
    fun detach(value: Observer) { if (observer.get() === value) observer.clear() }
    fun available(): Boolean = coordinator.snapshot() == null

    fun export(ctx: Context, uri: Uri, password: CharArray, legacy: Boolean): Boolean {
        val app = ctx.applicationContext
        return withPassword(if (legacy) BackupJobKind.EXPORT_LEGACY else BackupJobKind.EXPORT, password) { control ->
            control.check()
            val archive = if (legacy) BackupManager.exportTelegram(app, password)
                else BackupManager.export(app, password)
            try { control.check(); BackupDocumentIo.write(app, uri, archive, control) }
            finally { archive.fill(0) }
            BackupJobResult.Saved
        }
    }

    fun readBackup(ctx: Context, uri: Uri, password: CharArray): Boolean {
        val app = ctx.applicationContext
        return withPassword(BackupJobKind.READ_BACKUP, password) { control ->
            val bytes = BackupDocumentIo.read(app, uri, PortableBackupCodec.MAX_ENVELOPE_BYTES, control)
            try {
                control.check()
                BackupJobResult.Import(PortableBackupCodec.decryptTargetImport(bytes, password))
            } finally { bytes.fill(0) }
        }
    }
    fun readCsv(ctx: Context, uri: Uri): Boolean {
        val app = ctx.applicationContext
        return coordinator.start(BackupJobKind.READ_CSV) { _, control ->
            val bytes = BackupDocumentIo.read(app, uri, TargetBackupCodec.MAX_PLAINTEXT_BYTES, control)
            try {
                control.check()
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                BackupJobResult.Csv(TargetCsv.parse(text), TargetCsv.isLegacy(text))
            } finally { bytes.fill(0) }
        }
    }
    fun apply(ctx: Context, id: Long): Boolean {
        val app = ctx.applicationContext
        val kind = when (coordinator.snapshot()?.takeIf { it.id == id }?.value) {
            is BackupJobResult.Import -> BackupJobKind.APPLY_BACKUP
            is BackupJobResult.Csv -> BackupJobKind.APPLY_CSV
            else -> return false
        }
        return coordinator.start(kind, cancellable = false, previousId = id) { previous, _ ->
            when (previous) {
                is BackupJobResult.Import -> {
                    TargetRestore.stage(app, previous.data)
                    BackupManager.recoverPending(app)
                }
                is BackupJobResult.Csv -> {
                    if (previous.legacy) BackupManager.importHistory(app,
                        previous.rows.getValue(TargetId.TELEGRAM).map {
                            it.row.copy(kind = Timeline.canonicalKind(it.row.kind))
                        }) else BackupManager.importHistories(app, previous.rows)
                }
                else -> error("No validated import")
            }
            BackupJobResult.Applied
        }
    }
    fun recover(ctx: Context): Boolean {
        val app = ctx.applicationContext
        return coordinator.start(BackupJobKind.RECOVER, cancellable = false) { _, _ ->
            BackupManager.recoverPending(app)
            BackupJobResult.Applied
        }
    }
    private fun withPassword(kind: BackupJobKind, password: CharArray,
        operation: (BackupJobControl) -> BackupJobResult): Boolean {
        val accepted = coordinator.start(kind, sensitive = listOf(password)) { _, control ->
            try { operation(control) } finally { password.fill('\u0000') }
        }
        if (!accepted) password.fill('\u0000')
        return accepted
    }
}
