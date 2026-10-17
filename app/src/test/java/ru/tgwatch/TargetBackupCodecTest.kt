package ru.tgwatch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TargetBackupCodecTest {
    @Test fun unknownSettingIsRejectedBeforeItsValueCanConsumeMemory() {
        // The tokenizer prefetches one character; allocating the field value must still be forbidden.
        val prefix = "{\"settings\":{\"unrecognized\":\""
        val source = object : java.io.Reader() {
            private var position = 0
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                if (position == prefix.length) throw AssertionError("Unknown setting value was consumed")
                val size = minOf(length, prefix.length - position)
                prefix.toCharArray(buffer, offset, position, position + size)
                position += size
                return size
            }
            override fun close() = Unit
        }
        assertThrows(IllegalArgumentException::class.java) { TargetBackupReader(source).read() }
    }
    private fun sample(): TargetBackupSnapshot = TargetBackupSnapshot(10_000,
        mapOf("interval_sec" to 30, "notify_slow" to true),
        TargetId.entries.map(TargetDefinition::default), TargetId.entries.associateWith { id -> when (id) {
            TargetId.TELEGRAM -> listOf(TargetObservation(Observation(1_000, 2_000, "DOWN"), 0))
            TargetId.MATRIX -> listOf(TargetObservation(Observation(2_000, 3_000, "OK", 80), 2))
            else -> emptyList()
        } }, listOf("local log 🛰️"))

    @Test fun v3RoundTripExcludesCredentialsAndUsesSevenElementTuples() {
        val original = sample()
        val encoded = TargetBackupCodec.encode(original)
        val json = JSONObject(encoded.toString(Charsets.UTF_8))
        assertEquals(3, json.getInt("formatVersion"))
        assertEquals(7, json.getJSONArray("observations").getJSONArray(0).length())
        assertEquals("telegram", json.getJSONArray("observations").getJSONArray(0).getString(0))
        assertEquals("DOWN", json.getJSONArray("observations").getJSONArray(0).getString(3))
        assertEquals(original, TargetBackupCodec.decode(encoded))
        for (field in listOf("webhook_url", "automation_token", "extra_probe_custom_url")) {
            assertThrows(IllegalArgumentException::class.java) {
                TargetBackupCodec.encode(original.copy(globalSettings = original.globalSettings +
                    (field to "https://private.example/credential")))
            }
        }
        assertFalse(encoded.toString(Charsets.UTF_8).contains("https://"))
    }

    @Test fun rejectDuplicateIdsBadTupleUnknownKeysAndDepth() {
        val text = TargetBackupCodec.encode(sample()).toString(Charsets.UTF_8)
        fun reject(json: String) = assertThrows(IllegalArgumentException::class.java) {
            TargetBackupCodec.decode(json.toByteArray(Charsets.UTF_8))
        }
        reject(text.dropLast(1) + ",\"unexpected\":true}")
        reject(text.replace("\"createdAt\":10000", "\"createdAt\":10000,\"createdAt\":10000"))
        reject(text.replace("[\"telegram\",1000,", "[\"telegram\",1000.0,"))
        reject(text.replace("[\"telegram\",1000,", "[\"telegram\",1e3,"))
        reject(JSONObject(text).apply {
            getJSONArray("targets").put(1, getJSONArray("targets").getJSONObject(0))
        }.toString())
        reject(JSONObject(text).apply {
            getJSONArray("targets").getJSONObject(0).put("endpoint", "https://private.example")
        }.toString())
        for (tuple in listOf(JSONArray("[\"telegram\",1,2,\"OK\",-1,0]"),
            JSONArray("[\"telegram\",1,2,\"OK\",-1,0,0,0]"),
            JSONArray("[\"unknown\",1,2,\"OK\",-1,0,0]"),
            JSONArray("[\"telegram\",1,2,\"TG_DOWN\",-1,0,0]"),
            JSONArray("[\"telegram\",1,2,\"OK\",-2,0,0]"),
            JSONArray("[\"telegram\",1,2,\"OK\",-1,-1,0]"),
            JSONArray("[\"telegram\",1,2,\"OK\",-1,0,-1]"))) {
            reject(JSONObject(text).put("observations", JSONArray().put(tuple)).toString())
        }
        reject(text.replace("\"observations\":[", "\"observations\":[" + "[".repeat(11)))
        reject(text + "null")
    }

    @Test fun limitsArePerTargetAndLegacyLimitsStayUnchanged() {
        val original = sample()
        for ((id, count) in listOf(TargetId.TELEGRAM to 65_001, TargetId.MATRIX to 11_001)) {
            assertThrows(IllegalArgumentException::class.java) {
                TargetBackupCodec.encode(original.copy(observations = original.observations +
                    (id to List(count) { index -> TargetObservation(Observation(index.toLong(), index + 1L, "OK"), 0) })))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetBackupCodec.encode(original.copy(log = List(151) { "line" }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetBackupCodec.encode(original.copy(log = listOf("x".repeat(4_097))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetBackupCodec.decode(ByteArray(16 * 1024 * 1024 + 1))
        }
        assertEquals(8 * 1024 * 1024, BackupCodec.MAX_PLAINTEXT_BYTES)
        assertEquals(65_000, BackupCodec.MAX_OBSERVATIONS)
        assertEquals(8 * 1024 * 1024 + 56, BackupCrypto.MAX_ENVELOPE_BYTES)
    }

    @Test fun endpointAndClockMetadataDoNotInventChecks() {
        val original = sample()
        val metadata = TargetObservation(Observation(3_000, 3_000, "UNKNOWN", clockEpoch = 8), 3)
        val restored = TargetBackupCodec.decode(TargetBackupCodec.encode(original.copy(observations =
            original.observations + (TargetId.MATRIX to listOf(metadata)))))
        val row = restored.observations.getValue(TargetId.MATRIX).single()
        assertTrue(row.row.isEpochMetadata)
        assertEquals(3L, row.endpointRevision)
        assertEquals(0, Timeline.stats(listOf(row.row), 0, 10_000).checks)
    }

    @Test fun maximumBackupFits128MiB() {
        System.getProperty("tgwatch.backup.heap.limit")?.toLong()?.let { limit ->
            assertTrue("The actual fork must respect the configured heap", Runtime.getRuntime().maxMemory() <= limit)
        }
        val definitions = TargetId.entries.map(TargetDefinition::default)
        val rows = TargetId.entries.associateWith { id ->
            val count = if (id == TargetId.TELEGRAM) 65_000 else 11_000
            List(count) { index ->
                val at = Long.MAX_VALUE - (count - index) * 1_000L
                TargetObservation(Observation(at, at + 1_000, "PARTIAL", Long.MAX_VALUE, Long.MAX_VALUE), Long.MAX_VALUE)
            }
        }
        val original = TargetBackupSnapshot(Long.MAX_VALUE, emptyMap(), definitions, rows,
            List(150) { "\u0000".repeat(4_096) })
        val encoded = TargetBackupCodec.encode(original)
        assertTrue(encoded.size <= 16 * 1024 * 1024)
        val decoded = TargetBackupCodec.decode(encoded)
        assertEquals(98_000, decoded.observations.values.sumOf { it.size })
        assertEquals(original, decoded)
    }
}
