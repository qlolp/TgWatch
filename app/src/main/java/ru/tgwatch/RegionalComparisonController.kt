package ru.tgwatch

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast

internal class RegionalComparisonUiState {
    var a: RegionalComparisonSnapshot? = null
    var latest: RegionalComparisonPair? = null
    var pending: RegionalComparisonCollector? = null
    var capturingB = false
    var generation = -1L
    var serial = 0L
    var saving = false
    var message = R.string.regional_compare_ready
}

/** Main-thread coordinator; all disk work is delegated with the generation captured at A's start. */
internal class RegionalComparisonController(private val ctx: Context, val state: RegionalComparisonUiState,
    changed: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private var changed: (() -> Unit)? = changed

    fun start(isB: Boolean, label: String) {
        if (state.saving || state.pending != null) return
        val enabled = enabled()
        val generation = RegionalRepository.generation(ctx)
        val compatible = !isB || state.a?.readings?.keys == enabled && state.generation == generation
        val problem = when {
            !compatible -> R.string.regional_compare_changed
            runCatching { RegionalComparisonRules.label(label) }.isFailure -> R.string.regional_compare_bad_label
            else -> null
        }
        if (problem != null) message(problem) else request(isB, label, enabled, generation)
    }

    private fun request(isB: Boolean, label: String, enabled: Set<RegionalService>, generation: Long) {
        val elapsed = SystemClock.elapsedRealtime()
        val at = System.currentTimeMillis()
        val requested = !BackupManager.hasPending() && enabled.isNotEmpty() && MonitorService.requestRegionalCheck()
        if (!requested) { message(R.string.regional_enable_first); return }
        EgressCountryUi.refreshActive(ctx, force = true)
        val capture = runCatching {
            RegionalComparisonCollector(elapsed, RegionalContext.active(ctx), enabled, label, at)
        }.getOrNull()
        if (capture == null || generation != RegionalRepository.generation(ctx)) {
            message(R.string.regional_compare_changed); return
        }
        if (!isB) { state.a = null; state.latest = null; state.generation = generation }
        state.pending = capture
        state.capturingB = isB
        state.serial++
        changed?.invoke()
    }

    fun tick() {
        val capture = state.pending ?: return
        val allowed = Prefs.isEnabled(ctx) && MonitorService.running && !BackupManager.hasPending() &&
            state.generation == RegionalRepository.generation(ctx)
        if (!allowed) { cancel(R.string.regional_compare_changed); return }
        val current = RegionalContext.active(ctx)
        val enabled = enabled()
        val now = SystemClock.elapsedRealtime()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        RegionalService.entries.forEach { service ->
            val result = RegionalStateStore.get(service)
            if (result != null && result.networkHandle == cm.activeNetwork?.networkHandle &&
                result.configurationRevision == RegionalPreferences.revision(ctx)) {
                capture.offer(RegionalJournalObserver.entry(result, current), result.checkedElapsed,
                    now, current, enabled)
            }
        }
        val finishContext = RegionalContext.active(ctx)
        val completed = capture.finish(SystemClock.elapsedRealtime(), System.currentTimeMillis(),
            finishContext, enabled())
        when {
            capture.invalidated -> cancel(R.string.regional_compare_changed)
            completed != null -> finish(completed)
        }
    }

    private fun finish(completed: RegionalComparisonSnapshot) {
        state.pending = null
        val a = state.a
        when {
            !state.capturingB -> { state.a = completed; state.message = R.string.regional_compare_a_ready }
            a == null || completed.at < a.at -> cancel(R.string.regional_compare_changed)
            else -> save(RegionalComparisonPair(a, completed))
        }
    }

    internal fun save(pair: RegionalComparisonPair) {
        state.latest = pair
        state.saving = true
        val serial = ++state.serial
        val generation = state.generation
        RegionalComparisonRepository.save(ctx, pair, generation).whenComplete { saved, failure -> ui.post {
            if (serial == state.serial) {
                val outcome = RegionalComparisonCompletion.outcome(generation, RegionalRepository.generation(ctx),
                    saved == true && failure == null)
                state.saving = false
                state.message = when (outcome) {
                    RegionalComparisonSaveOutcome.SAVED -> R.string.regional_compare_saved
                    RegionalComparisonSaveOutcome.FAILED -> R.string.regional_compare_save_error
                    RegionalComparisonSaveOutcome.REPLACED -> R.string.regional_compare_changed
                }
                if (outcome != RegionalComparisonSaveOutcome.FAILED) { state.a = null; state.latest = null }
                changed?.invoke()
            }
        } }
    }

    fun cancel(message: Int = R.string.regional_compare_cancelled) {
        state.pending = null
        state.message = message
        state.serial++
        changed?.invoke()
    }

    fun clear() {
        cancel()
        state.a = null
        state.latest = null
        val serial = state.serial
        RegionalComparisonRepository.clear(ctx, RegionalRepository.generation(ctx)).whenComplete { cleared, failure ->
            ui.post {
                if (serial == state.serial) {
                    state.message = if (cleared == true && failure == null) R.string.regional_compare_cleared
                        else R.string.regional_compare_save_error
                    changed?.invoke()
                }
            }
        }
    }

    private fun message(resource: Int) { Toast.makeText(ctx, resource, Toast.LENGTH_LONG).show() }
    private fun enabled(): Set<RegionalService> = RegionalService.entries
        .filter { RegionalPreferences.enabled(ctx, it) }.toSet()
    fun close() { changed = null }
}
