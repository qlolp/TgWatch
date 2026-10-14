package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticSnapshotTest {
    private fun snapshot(outcomes: List<ProbeOutcome> = emptyList()) = DiagnosticSnapshot(
        appVersion = "1.13.0", appVersionCode = 1013000, sdkInt = 35,
        running = true, status = MonitorService.State(status = MonitorService.Status.PARTIAL,
            checkedAt = 1_000, reason = "SECRET_TOKEN user@example.org 192.168.1.4",
            diagnostics = "password=SECRET_PASSWORD private raw log"), stale = false,
        notificationsAllowed = true, exactAlarmsAllowed = false, batteryExempt = false,
        deviceIdleObserved = true, powerSave = false, userUnlocked = true,
        dailyStats = TimeStats(60_000, 30_000, 10_000, 20_000, 50_000, 30_000, 7, 125),
        endpoints = outcomes, scheduling = ScheduleDiagnostics.Snapshot())

    @Test fun formatterExcludesPrivateReasonsLogsAndUnknownEndpoints() {
        val output = snapshot(listOf(
            ProbeOutcome("api.telegram.org", ProbeGroup.WEB, true, 125,
                "SECRET_TOKEN user@example.org 192.168.1.4"),
            ProbeOutcome("192.168.1.4 SECRET_PASSWORD", ProbeGroup.CONTROL, false, detail = "private")
        )).toJson()
        listOf("SECRET_TOKEN", "SECRET_PASSWORD", "user@example.org", "192.168.1.4", "reason", "diagnostics", "raw log", "settings")
            .forEach { assertFalse(it, output.contains(it)) }
        val json = JSONObject(output)
        assertEquals("PARTIAL", json.getJSONObject("state").getString("status"))
        assertEquals(1, json.getJSONArray("endpoints").length())
        assertEquals("api.telegram.org", json.getJSONArray("endpoints").getJSONObject(0).getString("endpoint"))
        assertEquals(60_000, json.getJSONObject("last_24_hours").getLong("ok_ms"))
    }
    @Test fun exportIsBoundedAndEndpointDetailsCannotEscapeWhitelist() {
        val endpoints = List(10_000) { ProbeOutcome("MTProto DC1", ProbeGroup.MTPROTO, false, detail = "x".repeat(10_000)) }
        val output = snapshot(endpoints).toJson()
        assertTrue(output.toByteArray().size <= DiagnosticSnapshot.MAX_BYTES)
        assertEquals(1, JSONObject(output).getJSONArray("endpoints").length())
        assertFalse(output.contains("xxxx"))
    }
    @Test fun untrustedVersionNameIsNotExported() {
        val output = snapshot().copy(appVersion = "secret@example.org 10.0.0.1").toJson()
        assertEquals("unknown", JSONObject(output).getJSONObject("app").getString("version"))
    }
    @Test fun bundledDualStackDcNamesAreExportedWithoutAddressesOrDetails() {
        val outcomes = MtProtoEndpoints.all.map {
            ProbeOutcome(it.name, it.group, true, 42, "SECRET_DETAIL ${it.address}")
        } + listOf(
            ProbeOutcome("MTProto DC1 (IPv6)", ProbeGroup.EXTRA, true, detail = "wrong group"),
            ProbeOutcome("2001:db8::dead", ProbeGroup.MTPROTO, false, detail = "unknown address"))
        val output = snapshot(outcomes).toJson()
        val exported = JSONObject(output).getJSONArray("endpoints")
        assertEquals(10, exported.length())
        assertEquals(MtProtoEndpoints.all.map { it.name }.toSet(),
            (0 until exported.length()).map { exported.getJSONObject(it).getString("endpoint") }.toSet())
        assertTrue((0 until exported.length()).all {
            exported.getJSONObject(it).getString("group") == "MTPROTO"
        })
        for (privateValue in MtProtoEndpoints.all.map { it.address } + listOf("SECRET_DETAIL", "2001:db8::dead")) {
            assertFalse(privateValue, output.contains(privateValue))
        }
    }
    @Test fun reportSectionsCannotContainRawHistoryLogPreferencesOrIdentifiers() {
        val json = JSONObject(snapshot().toJson())
        assertEquals(setOf("schema_version", "app", "os", "flags", "state", "last_24_hours", "endpoints", "scheduling"),
            json.keyNames())
        assertEquals(setOf("running", "status", "checked_at_ms", "stale", "latency_ms"), json.getJSONObject("state").keyNames())
        assertEquals(setOf("ok_ms", "down_ms", "partial_ms", "offline_ms", "unknown_ms", "checks", "outage_count", "availability_percent"),
            json.getJSONObject("last_24_hours").keyNames())
        assertEquals(setOf("sdk_int"), json.getJSONObject("os").keyNames())
        assertFalse(json.has("history"))
        assertFalse(json.has("log"))
        assertFalse(json.has("preferences"))
    }
    @Test fun scheduledFieldsDoNotAssertDozeCause() {
        val json = JSONObject(snapshot().toJson()).getJSONObject("scheduling")
        assertTrue(json.has("idle_observed_arrivals"))
        assertFalse(json.has("doze_caused_delays"))
    }
}

// android.jar's org.json has no keySet(); since Kotlin 2.4 tests compile against it first.
private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()
