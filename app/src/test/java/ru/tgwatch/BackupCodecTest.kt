package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val base = """{"formatVersion":1,"createdAt":123,"settings":{},"observations":[],"log":[]}"""
    private fun decode(text: String) = BackupCodec.decode(text.toByteArray(Charsets.UTF_8))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid backup accepted") } catch (_: IllegalArgumentException) { }
    }
    private fun sample() = BackupSnapshot(1_700_000_000_000, linkedMapOf(
        "power_profile" to "CUSTOM", "interval_sec" to 10, "keep_awake" to true,
        "vibrate" to false, "vibrate_partial" to true, "vibrate_offline" to false,
        "vibrate_recovery" to true, "notify_recovery" to true, "event_sound" to false,
        "vibration_pattern" to "LONG", "quiet_hours" to true,
        "quiet_start_hour" to 23, "quiet_end_hour" to 0,
        "quiet_start_minute" to 1439, "quiet_end_minute" to 0,
        "daily_summary" to true, "daily_summary_minute" to 1439),
        listOf(Observation(0, 600_000, "OK", Long.MAX_VALUE, Long.MAX_VALUE),
            Observation(Long.MAX_VALUE - 1, Long.MAX_VALUE, "UNKNOWN")),
        listOf("Проверка: связь восстановлена 🛰️", "line\nwith\t\"quotes\""))

    @Test fun roundTripPreservesSettingsUnicodeAndLongExtremes() {
        assertEquals(sample(), BackupCodec.decode(BackupCodec.encode(sample())))
    }
    @Test fun acceptsEachKnownProfilePatternAndStatus() {
        for (profile in listOf("ECONOMY", "BALANCED", "FREQUENT", "CUSTOM")) {
            for (pattern in listOf("STANDARD", "SHORT", "LONG")) {
                val snapshot = BackupSnapshot(0, mapOf("power_profile" to profile, "vibration_pattern" to pattern),
                    listOf("OK", "TG_DOWN", "PARTIAL", "NO_NETWORK", "UNKNOWN").map { Observation(0, 1, it) }, emptyList())
                assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)))
            }
        }
    }
    @Test fun rejectsUnknownMissingAndWrongRootFields() {
        for (text in listOf(base.replace("\"formatVersion\":1", "\"formatVersion\":3"),
            base.replace("\"createdAt\":123,", ""), base.replace("\"log\":[]", "\"log\":[],\"enabled\":true"),
            base.replace("\"settings\":{}", "\"settings\":[]"), base.replace("\"observations\":[]", "\"observations\":{}"),
            base.replace("\"log\":[]", "\"log\":[null]"), "[]", base + "{}", base.dropLast(1))) rejected { decode(text) }
    }
    @Test fun readsLegacyV1AndWritesV2WithoutReinterpretingOneMillisecondUnknown() {
        val row = """{"at":0,"until":1,"kind":"UNKNOWN","latencyMs":-1,"clockEpoch":1}"""
        val text = base.replace("\"observations\":[]", "\"observations\":[$row]")
        val legacy = decode(text)
        assertEquals(listOf(Observation(0, 1, "UNKNOWN", clockEpoch = 1)), legacy.observations)
        assertEquals(1, Timeline.stats(legacy.observations, 0, 2).unknownChecks)
        val written = BackupCodec.encode(legacy).toString(Charsets.UTF_8)
        assertTrue(written.contains("\"formatVersion\":2"))
        assertEquals(legacy, decode(written))
    }
    @Test fun onlyV2AcceptsExplicitZeroWidthEpochMetadata() {
        val row = """{"at":0,"until":0,"kind":"UNKNOWN","latencyMs":-1,"clockEpoch":1}"""
        val text = base.replace("\"observations\":[]", "\"observations\":[$row]")
        rejected { decode(text) }
        val v2 = text.replace("\"formatVersion\":1", "\"formatVersion\":2")
        assertEquals(listOf(Observation(0, 0, "UNKNOWN", clockEpoch = 1)), decode(v2).observations)
        for (invalid in listOf(v2.replace("UNKNOWN", "OK"), v2.replace("\"latencyMs\":-1", "\"latencyMs\":0"))) {
            rejected { decode(invalid) }
        }
    }
    @Test fun rejectsCoercionOverflowFractionalAndNegativeRootNumbers() {
        for (number in listOf("\"123\"", "123.0", "1e3", "9223372036854775808", "-1", "null", "true")) {
            rejected { decode(base.replace("123", number)) }
        }
        rejected { decode(base.replace("\"formatVersion\":1", "\"formatVersion\":1.0")) }
    }
    @Test fun settingsWhitelistTypesAndRangesAreStrict() {
        val invalid = listOf("\"enabled\":false", "\"last_state\":\"OK\"", "\"keep_awake\":1", "\"quiet_hours\":\"true\"",
            "\"interval_sec\":9", "\"interval_sec\":121", "\"interval_sec\":30.0", "\"interval_sec\":\"30\"",
            "\"interval_sec\":4294967326", "\"power_profile\":\"other\"", "\"vibration_pattern\":\"other\"",
            "\"quiet_start_hour\":24", "\"quiet_end_hour\":-1", "\"quiet_start_minute\":1440", "\"quiet_end_minute\":-1",
            "\"daily_summary\":1", "\"daily_summary_minute\":1440", "\"daily_summary_minute\":-1")
        invalid.forEach { rejected { decode(base.replace("\"settings\":{}", "\"settings\":{$it}")) } }
        rejected { BackupCodec.encode(sample().copy(settings = mapOf("interval_sec" to 30L))) }
        rejected { BackupCodec.encode(sample().copy(settings = mapOf("enabled" to true))) }
    }
    @Test fun observationsRequireAllFieldsIntegralLongsAndSafeDurations() {
        val row = """{"at":0,"until":1,"kind":"OK","latencyMs":-1,"clockEpoch":0}"""
        val invalid = listOf(row.replace("\"at\":0", "\"at\":-1"), row.replace("\"until\":1", "\"until\":0"),
            row.replace("\"until\":1", "\"until\":600001"), row.replace("\"kind\":\"OK\"", "\"kind\":\"BAD\""),
            row.replace("\"latencyMs\":-1", "\"latencyMs\":-2"), row.replace("\"clockEpoch\":0", "\"clockEpoch\":-1"),
            row.replace("\"at\":0", "\"at\":0.1"), row.replace("\"until\":1", "\"until\":9223372036854775808"),
            row.replace("\"clockEpoch\":0", "\"clockEpoch\":\"0\""), row.replace(",\"clockEpoch\":0", ""),
            row.replace("\"kind\":\"OK\"", "\"kind\":null"), row.dropLast(1) + ",\"extra\":0}", "null")
        invalid.forEach { rejected { decode(base.replace("\"observations\":[]", "\"observations\":[$it]")) } }
        rejected { BackupCodec.encode(sample().copy(observations = listOf(Observation(0, 600001, "OK")))) }
    }
    @Test fun journalAndObservationCountBoundariesApplyToEncodeAndDecode() {
        val boundary = BackupSnapshot(0, emptyMap(), List(65_000) { Observation(it.toLong(), it + 1L, "OK") }, List(150) { "x".repeat(4096) })
        assertTrue("Boundary snapshot did not round-trip", boundary == BackupCodec.decode(BackupCodec.encode(boundary)))
        rejected { BackupCodec.encode(boundary.copy(observations = boundary.observations + Observation(0, 1, "OK"))) }
        rejected { BackupCodec.encode(boundary.copy(log = boundary.log + "x")) }
        rejected { BackupCodec.encode(boundary.copy(log = listOf("x".repeat(4097)))) }
        rejected { decode(base.replace("\"log\":[]", "\"log\":[" + List(151) { "\"x\"" }.joinToString(",") + "]")) }
        rejected { decode(base.replace("\"log\":[]", "\"log\":[\"" + "x".repeat(4097) + "\"]")) }
        rejected { decode(base.replace("\"observations\":[]", "\"observations\":[" + List(65_001) { "null" }.joinToString(",") + "]")) }
    }
    @Test fun rejectsOversizedMalformedUtf8DuplicateAndLenientJsonBeforeParsing() {
        rejected { BackupCodec.decode(ByteArray(BackupCodec.MAX_PLAINTEXT_BYTES + 1)) }
        rejected { BackupCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        rejected { decode(base.replace("123", "123,\"createdAt\":124")) }
        rejected { decode(base.replace("\"createdAt\"", "createdAt")) }
        rejected { decode(base.replace("\"createdAt\"", "'createdAt'")) }
        rejected { decode(base.replace("\"log\":[]", "\"log\":[],")) }
        rejected { decode(base + '\u0000') }
        rejected { decode("[".repeat(1000) + "]".repeat(1000)) }
    }
    @Test fun rejectsNestedArrayValuesInObservationRecords() {
        val text = base.replace("\"observations\":[]", "\"observations\":[" +
            List(7) { "[" + List(65_000) { "0" }.joinToString(",") + "]" }.joinToString(",") + "]")
        rejected { decode(text) }
    }
    @Test fun stringEscapesDoNotCountAsNestingAndDecodedDuplicateNamesReject() {
        val snapshot = BackupSnapshot(0, emptyMap(), emptyList(), listOf("[".repeat(1000) + "\\\"{}]"))
        assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)))
        rejected { decode(base.replace("123", "123,\"created\\u0041t\":124")) }
        rejected { decode(base.replace("\"log\":[]", "\"log\":[\"\\ud800\"]")) }
        rejected { BackupCodec.encode(snapshot.copy(log = listOf("\ud800"))) }
    }
    @Test fun allTwentySixSettingsIncludingEventsDailySummaryAndExtraProbesRoundTripWithStrictTypes() {
        val snapshot = sample().copy(settings = sample().settings + mapOf("notify_outage" to true,
            "notify_partial" to false, "sound_outage" to false, "sound_partial" to true, "sound_recovery" to false,
            "extra_probe_matrix" to true, "extra_probe_whatsapp" to false, "extra_probe_custom" to true,
            "extra_probe_custom_url" to "https://status.example.org/health"))
        assertEquals(26, snapshot.settings.size)
        assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)))
        for (key in listOf("notify_outage", "notify_partial", "sound_outage", "sound_partial", "sound_recovery",
            "daily_summary", "extra_probe_matrix", "extra_probe_whatsapp", "extra_probe_custom")) {
            rejected { BackupCodec.encode(snapshot.copy(settings = snapshot.settings + (key to "true"))) }
            rejected { decode(base.replace("\"settings\":{}", "\"settings\":{\"$key\":1}")) }
        }
        rejected { BackupCodec.encode(snapshot.copy(settings = snapshot.settings + ("daily_summary_minute" to 1440))) }
        rejected { decode(base.replace("\"settings\":{}", "\"settings\":{\"daily_summary_minute\":true}")) }
    }
    @Test fun customProbeUrlIsValidatedAndWebhookCredentialIsNeverAccepted() {
        val empty = BackupSnapshot(0, mapOf("extra_probe_custom_url" to ""), emptyList(), emptyList())
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty)))
        for (bad in listOf("http://example.org/", "https://user:pass@example.org/", "https://example.org/?token=1",
            "https://example.org/#x", " https://example.org/", "https://" + "a".repeat(250) + ".org/", "javascript:x")) {
            rejected { BackupCodec.encode(empty.copy(settings = mapOf("extra_probe_custom_url" to bad))) }
        }
        rejected { decode(base.replace("\"settings\":{}", "\"settings\":{\"extra_probe_custom_url\":1}")) }
        rejected { decode(base.replace("\"settings\":{}", "\"settings\":{\"extra_probe_custom_url\":\"ftp://x/\"}")) }
        for (key in listOf("webhook_url", "webhook_enabled", "url", "token")) {
            rejected { decode(base.replace("\"settings\":{}", "\"settings\":{\"$key\":\"https://ntfy.sh/t\"}")) }
        }
    }
}
