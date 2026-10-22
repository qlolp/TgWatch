package ru.tgwatch

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper

/** Called from the existing monitor. It shares its scheduler, admission budget and physical probes. */
internal class RegionalWatchController(private val ctx: Context, private val active: () -> Boolean) {
    private class Binding(val metadata: RegionalWatchMetadata) {
        val watch = RegionalChangeWatch(metadata.request)
        var pending = false
    }
    private val bindings = mutableMapOf<RegionalService, Binding>()
    private val notifications = RegionalPublicNotifications(ctx)
    private val main = Handler(Looper.getMainLooper())

    @Synchronized fun observe(result: RegionalResult, now: Long, maxAgeMs: Long): Long? {
        val binding = binding(result.service) ?: return null
        return if (!fresh(result, now, maxAgeMs)) { binding.watch.resetEvidence(); null } else {
            val connection = RegionalContext.current(ctx, result.networkHandle, result.configurationRevision)
            binding.watch.observe(result.status, connection.token, result.checkedElapsed, now, maxAgeMs)
            binding.watch.confirmationDue?.coerceAtLeast(now)
        }
    }

    @Synchronized fun reconcile(result: RegionalResult, now: Long, maxAgeMs: Long) {
        val binding = binding(result.service) ?: return
        if (!fresh(result, now, maxAgeMs)) { binding.watch.resetEvidence(); return }
        val scope = RegionalContext.active(ctx).token
        val ready = result.status == RegionalStatus.WEB_REACHABLE && binding.watch.ready(scope, now, maxAgeMs)
        if (ready && !binding.pending && notifications.post(result, binding.metadata)) consume(result.service, binding)
    }

    private fun consume(service: RegionalService, binding: Binding) {
        binding.pending = true
        val generation = RegionalRepository.generation(ctx)
        RegionalRepository.transaction(ctx, generation) {
            RegionalWatchPreferences.consume(ctx, service, binding.metadata)
        }.whenComplete { saved, failure -> main.post {
            synchronized(this) {
                binding.pending = false
                if (saved == true && failure == null) binding.watch.delivered()
            }
        } }
    }

    private fun binding(service: RegionalService): Binding? {
        val metadata = RegionalWatchPreferences.current(ctx, service)
        if (metadata == null) { bindings.remove(service); return null }
        return bindings[service]?.takeIf { it.metadata == metadata }
            ?: Binding(metadata).also { bindings[service] = it }
    }

    private fun fresh(result: RegionalResult, now: Long, maxAgeMs: Long): Boolean =
        RegionalFreshness.current(result, RegionalFreshnessScope(now,
            ctx.getSystemService(ConnectivityManager::class.java).activeNetwork?.networkHandle,
            RegionalPreferences.revision(ctx), active() && Prefs.isEnabled(ctx) && !BackupManager.hasPending(),
            RegionalPreferences.enabled(ctx, result.service), maxAgeMs))

    @Synchronized fun resetEvidence(service: RegionalService? = null) {
        if (service == null) bindings.values.forEach { it.watch.resetEvidence() }
        else bindings[service]?.watch?.resetEvidence()
    }
}
