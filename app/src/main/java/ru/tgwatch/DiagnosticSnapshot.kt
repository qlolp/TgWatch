package ru.tgwatch

import org.json.JSONArray
import org.json.JSONObject

data class TargetDiagnostic(val state: TargetSnapshot, val running: Boolean, val stale: Boolean,
    val stats: TimeStats?, val failure: HistoryFailure? = null)

/** Explicit allowlist serializer. Never serialize State, ProbeOutcome, preferences, or files directly. */
data class DiagnosticSnapshot(
    val appVersion: String,
    val appVersionCode: Long,
    val sdkInt: Int,
    val running: Boolean,
    val status: MonitorService.State,
    val stale: Boolean,
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val batteryExempt: Boolean,
    val deviceIdleObserved: Boolean,
    val powerSave: Boolean,
    val userUnlocked: Boolean,
    val dailyStats: TimeStats?,
    val endpoints: List<ProbeOutcome>,
    val scheduling: ScheduleDiagnostics.Snapshot,
    val monitorHealth: MonitorHealth = MonitorHealth(),
    val targets: List<TargetDiagnostic> = emptyList(),
    val crashes: CrashJournal.Summary = CrashJournal.Summary(0, null),
    /** ISO egress country only; the address is never included. */
    val egressCountry: String? = null,
) {
    fun toJson(): String {
        val json = JSONObject().put("schema_version", SCHEMA_VERSION)
            .put("app", JSONObject()
                .put("version", appVersion.takeIf { VERSION.matches(it) } ?: "unknown")
                .put("version_code", appVersionCode.coerceAtLeast(0)))
            .put("os", JSONObject().put("sdk_int", sdkInt.coerceIn(0, MAX_SDK)))
            .put("flags", flagsJson())
            .put("state", stateJson())
            .put("last_24_hours", statsJson())
            .put("endpoints", endpointsJson())
            .put("scheduling", schedulingJson())
            .put("monitor_health", healthJson())
            .put("targets", targetsJson())
            .put("crashes", crashesJson())
        return json.toString(2).also { check(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    private fun flagsJson() = JSONObject()
        .put("notifications_allowed", notificationsAllowed)
        .put("exact_alarms_allowed", exactAlarmsAllowed)
        .put("battery_exempt", batteryExempt)
        .put("device_idle_observed", deviceIdleObserved)
        .put("power_save", powerSave)
        .put("user_unlocked", userUnlocked)

    private fun stateJson() = JSONObject()
        .put("running", running)
        .put("status", status.status.name)
        .put("checked_at_ms", status.checkedAt.coerceAtLeast(0))
        .put("stale", stale)
        .put("latency_ms", status.latencyMs.coerceIn(-1, MAX_LATENCY_MS))
        .put("egress_country", egressCountry?.takeIf { EgressCountry.isValid(it) } ?: JSONObject.NULL)

    private fun statsJson(stats: TimeStats? = dailyStats): Any = stats?.let { stats ->
        val coverage = CoverageStats.from(stats, CoverageStats.DAY_MS)
        JSONObject()
            .put("ok_ms", stats.okMs.coerceAtLeast(0))
            .put("down_ms", stats.downMs.coerceAtLeast(0))
            .put("partial_ms", stats.partialMs.coerceAtLeast(0))
            .put("offline_ms", stats.offlineMs.coerceAtLeast(0))
            .put("unknown_ms", stats.unknownMs.coerceAtLeast(0))
            .put("checks", stats.checks.coerceAtLeast(0))
            .put("outage_count", stats.outageCount.coerceAtLeast(0))
            .put("availability_percent", stats.uptimePercent.takeIf { it.isFinite() && it >= 0 } ?: JSONObject.NULL)
            .put("coverage_percent", coverage.percent ?: JSONObject.NULL)
            .put("assessed_ms", coverage.assessedMs)
            .put("window_ms", coverage.windowMs)
    } ?: JSONObject.NULL

    private fun targetsJson(): JSONArray = JSONArray().apply {
        targets.distinctBy { it.state.id }.take(TargetId.entries.size).forEach { goal ->
            val state = goal.state
            put(JSONObject().put("target_id", state.id.wire).put("running", goal.running)
                .put("status", state.status.name).put("checked_at_ms", state.checkedAtMs)
                .put("stale", goal.stale).put("latency_ms", state.latencyMs?.coerceAtMost(MAX_LATENCY_MS)
                    ?: JSONObject.NULL)
                .put("last_24_hours", statsJson(goal.stats))
                .put("storage_failure", goal.failure?.name ?: JSONObject.NULL))
        }
    }

    private fun healthJson() = JSONObject()
        .put("check_started_elapsed_ms", monitorHealth.checkStartedElapsed.coerceAtLeast(0))
        .put("not_measured_count", monitorHealth.notMeasuredCount.coerceAtLeast(0))
        .put("primary_busy", monitorHealth.primaryBusy.coerceIn(0, ProbeAdmission.PRIMARY_LIMIT))
        .put("secondary_busy", monitorHealth.secondaryBusy.coerceIn(0, ProbeAdmission.SECONDARY_LIMIT))
        .put("storage_failure", monitorHealth.storageFailure?.name ?: JSONObject.NULL)
        .put("recovery_state", monitorHealth.recoveryState.name)

    private fun endpointsJson(): JSONArray {
        val outcomes = JSONArray()
        endpoints.asSequence().filter { ENDPOINTS[it.endpoint] == it.group }.distinctBy { it.endpoint }
            .take(ENDPOINTS.size).forEach { outcomes.put(JSONObject()
                .put("endpoint", it.endpoint).put("group", it.group.name)
                .put("reachable", it.reachable).put("latency_ms", it.latencyMs.coerceIn(-1, MAX_LATENCY_MS))) }
        return outcomes
    }

    private fun crashesJson(): JSONObject {
        val last = crashes.last?.let(CrashJournal::redacted)
        return JSONObject().put("retained", crashes.retained.coerceIn(0, MAX_CRASH_RECORDS))
            .put("last", last?.let { record ->
                JSONObject().put("at_ms", record.atMs.coerceAtLeast(0))
                    .put("thread", record.thread.take(MAX_CRASH_THREAD_CHARS))
                    .put("type", record.type.take(MAX_CRASH_TYPE_CHARS))
                    .put("frames", JSONArray().apply {
                        record.frames.take(MAX_CRASH_FRAMES)
                            .forEach { frame -> put(frame.take(MAX_CRASH_FRAME_CHARS)) }
                    })
            } ?: JSONObject.NULL)
    }

    private fun schedulingJson() = JSONObject()
        .put("scope", "service_session")
        .put("scheduled_arrivals", scheduling.scheduledArrivals)
        .put("late_arrivals", scheduling.lateArrivals)
        .put("late_tolerance_ms", ScheduleDiagnostics.LATE_TOLERANCE_MS)
        .put("total_delay_ms", scheduling.totalDelayMs)
        .put("max_delay_ms", scheduling.maxDelayMs)
        .put("idle_observed_arrivals", scheduling.idleObservedArrivals)
        .put("manual_requests", scheduling.manualRequests)
        .put("overlapping_requests", scheduling.overlappingRequests)

    companion object {
        private const val SCHEMA_VERSION = 5
        const val MAX_BYTES = 16 * 1024
        private const val MAX_LATENCY_MS = 60_000L
        private const val MAX_SDK = 1_000
        private const val MAX_CRASH_RECORDS = 5
        private const val MAX_CRASH_FRAMES = 24
        private const val MAX_CRASH_FRAME_CHARS = 200
        private const val MAX_CRASH_THREAD_CHARS = 64
        private const val MAX_CRASH_TYPE_CHARS = 160
        private val VERSION = Regex("[0-9]{1,4}\\.[0-9]{1,3}(\\.[0-9]{1,3})?")
        private val ENDPOINTS = mapOf(
            "api.telegram.org" to ProbeGroup.WEB, "web.telegram.org" to ProbeGroup.WEB,
            "core.telegram.org" to ProbeGroup.WEB,
            "Yandex" to ProbeGroup.CONTROL, "Яндекс" to ProbeGroup.CONTROL,
            "Mail.ru" to ProbeGroup.CONTROL, "Google 204" to ProbeGroup.CONTROL,
        ) + MtProtoEndpoints.all.associate { it.name to it.group }
    }
}
