package ru.tgwatch

import org.json.JSONArray
import org.json.JSONObject

/** Strict endpoint and payload allowlist for the optional user-operated crash collector. */
internal object CrashReportingPolicy {
    const val SCHEMA_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 8 * 1024
    const val MAX_FRAMES = 24
    const val MAX_ENDPOINT_LENGTH = 512
    private const val MAX_APP_VERSION_LENGTH = 32

    fun normalizeEndpoint(raw: String?): String? =
        HttpsUrlPolicy.normalize(raw, MAX_ENDPOINT_LENGTH, allowQuery = false)

    fun payload(appVersion: String?, sdkInt: Int, crashClass: String, rawFrames: List<String>): ByteArray {
        val frames = JSONArray()
        rawFrames.asSequence().take(MAX_FRAMES).map(CrashMetadata::frame)
            .filter { it.startsWith("ru.tgwatch.") && it != "redacted" }
            .forEach(frames::put)
        val version = appVersion?.takeIf {
            it.length in 1..MAX_APP_VERSION_LENGTH &&
                it.all { char -> char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in ".-+_" }
        } ?: "unknown"
        val json = JSONObject().put("schema_version", SCHEMA_VERSION)
            .put("app_version", version)
            .put("android_sdk", sdkInt.coerceIn(1, 1000))
            .put("crash_class", CrashMetadata.type(crashClass))
            .put("stack_frames", frames)
        return json.toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_PAYLOAD_BYTES)
        }
    }
}
