package ru.tgwatch

import android.content.Context

/** Узкий контракт службы для первичной проверки Telegram: без доступа к остальному состоянию. */
internal interface PrimaryCheckHost {
    /** Общая блокировка публикации и смены сети: проверка ревизий и публикация идут под ней. */
    val publicationLock: Any
    fun acceptsRunningCheck(): Boolean
    fun currentScheduleRevision(): Long
    fun createProbe(): PrimaryAndroidProbe
    fun onNetworkKind(kind: NetworkKind)
    fun onMeasured(result: MonitorService.Probe)
    fun onUnmeasured(result: MonitorService.Probe)
}

/** Подготовка первичной проверки Telegram и публикация результата с проверкой ревизий. */
internal class PrimaryCheckController(
    private val context: Context,
    private val host: PrimaryCheckHost,
) {
    @Volatile private var settingsRevision: Long? = null

    fun beginCheck() {
        settingsRevision = TargetPreferences.fence.snapshot(TargetId.TELEGRAM).revision
    }

    fun finishCheck() {
        settingsRevision = null
    }

    fun performCheck(revision: Long): MonitorService.Probe {
        val result = host.createProbe().check(revision)
        host.onNetworkKind(result.networkKind)
        return MonitorService.Probe(result.status, result.latencyMs, AndroidText.resolve(context, result.reason),
            AndroidText.resolve(context, result.diagnostics), result.outcomes,
            result.extras?.let { MonitorService.ExtraCheck(it.token, it.probe, it.endpoints) })
    }

    /** Network callbacks and primary publication share this lock, including revision validation. */
    fun publishCheckedResult(result: MonitorService.Probe, revision: Long) {
        synchronized(host.publicationLock) {
            val current = TargetPreferences.fence.snapshot(TargetId.TELEGRAM).revision
            val currentSettings = settingsRevision?.let { it == current } ?: true
            if (!host.acceptsRunningCheck() || !TargetPreferences.definition(context, TargetId.TELEGRAM).enabled ||
                !currentSettings) return
            if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
            // A result from a network that has since changed is no observation: keep the prior state,
            // history and alerts. afterCheck() runs the replacement check one second later.
            if (revision != host.currentScheduleRevision()) return
            if (result.publishable) host.onMeasured(result) else host.onUnmeasured(result)
        }
    }
}
