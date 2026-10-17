package ru.tgwatch

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Process-owned workers remain bounded when a disk operation does not respond to interruption. */
object HistorySessions {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "tg-history") }
    @Volatile private var instance: HistorySessionQueue? = null
    val backpressured: Boolean get() = instance?.backpressured == true
    @Synchronized fun queue(ctx: Context): HistorySessionQueue {
        val app = ctx.applicationContext
        return instance ?: HistorySessionQueue(executor, {
            History.endSession(app, it)
            EventLog.add(app, Lang.t("Мониторинг остановлен", "Monitoring stopped"))
        }).also { instance = it }
    }
    fun awaitClosed(ctx: Context, timeoutMs: Long) {
        queue(ctx).closedBarrier().get(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }
}

object RecoveryCoordinator {
    private val gate = RecoveryGate(Executors.newSingleThreadExecutor { task -> Thread(task, "tg-recovery") })
    val state get() = gate.state
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        gate.start {
            BackupManager.recoverPending(app)
            TargetPersistence.initialize(app)
            PrivateEndpoints.migrateLegacy(app)
        }
    }
    fun onUserUnlocked(ctx: Context) {
        val app = ctx.applicationContext
        start(app)
        whenReady { HistorySessions.queue(app).exclusive {
            PrivateEndpoints.migrateLegacy(app)
            History.snapshot(app)
        } }
    }
    fun whenReady(action: () -> Unit) = gate.whenReady(action)
}
