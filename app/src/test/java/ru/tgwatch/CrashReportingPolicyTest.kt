package ru.tgwatch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingPolicyTest {
    @Test fun endpointMustBeHttpsAndCannotContainInlineCredentialsOrQueryData() {
        assertEquals("https://collector.example/crash-secret-path",
            CrashReportingPolicy.normalizeEndpoint(" https://collector.example/crash-secret-path "))
        listOf("http://collector.example/crash", "******collector.example/crash",
            "https://collector.example/crash?token=SECRET_TOKEN", "https://collector.example/crash#secret",
            "https://collector.example/${"x".repeat(CrashReportingPolicy.MAX_ENDPOINT_LENGTH)}")
            .forEach { assertEquals(null, CrashReportingPolicy.normalizeEndpoint(it)) }
    }

    @Test fun payloadHasOnlyVersionedAllowlistedFieldsAndRedactedFirstPartyFrames() {
        val secret = "SECRET_TOKEN https://private.example/path 192.0.2.7 user@example.org"
        val frames = listOf(
            StackTraceElement("ru.tgwatch.MonitorService", "onCreate", "MonitorService.kt", 42).toString(),
            StackTraceElement("ru.tgwatch.MonitorService", "onCreate", "/private/$secret.kt", 43).toString(),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 1).toString(),
            secret,
        )
        val bytes = CrashReportingPolicy.payload("1.2.3", 35, "java.lang.IllegalStateException", frames)
        val contents = bytes.toString(Charsets.UTF_8)
        val json = JSONObject(contents)

        assertEquals(setOf("schema_version", "app_version", "android_sdk", "crash_class", "stack_frames"),
            json.keys().asSequence().toSet())
        assertEquals(CrashReportingPolicy.SCHEMA_VERSION, json.getInt("schema_version"))
        assertEquals("1.2.3", json.getString("app_version"))
        assertEquals(35, json.getInt("android_sdk"))
        assertEquals("java.lang.IllegalStateException", json.getString("crash_class"))
        assertEquals(2, json.getJSONArray("stack_frames").length())
        assertTrue(contents, contents.contains("ru.tgwatch.MonitorService"))
        listOf("SECRET_TOKEN", "private.example", "192.0.2.7", "user@example.org", "java.lang.Thread",
            "webhook", "target criteria", "history")
            .forEach { assertFalse(contents, contents.contains(it)) }
        assertTrue(bytes.size <= CrashReportingPolicy.MAX_PAYLOAD_BYTES)
    }

    @Test fun payloadBoundsFramesAndRejectsUntrustedVersionText() {
        val frame = StackTraceElement("ru.tgwatch.MonitorService", "onCreate", "MonitorService.kt", 1).toString()
        val bytes = CrashReportingPolicy.payload("1.2.3 SECRET_TOKEN", 9_999,
            "untrusted SECRET_TOKEN", List(100) { frame })
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("unknown", json.getString("app_version"))
        assertEquals(1_000, json.getInt("android_sdk"))
        assertEquals("other", json.getString("crash_class"))
        assertEquals(CrashReportingPolicy.MAX_FRAMES, json.getJSONArray("stack_frames").length())
        assertFalse(bytes.toString(Charsets.UTF_8).contains("SECRET_TOKEN"))
        assertTrue(bytes.size <= CrashReportingPolicy.MAX_PAYLOAD_BYTES)
    }
}
