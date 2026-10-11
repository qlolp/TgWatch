package ru.tgwatch

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Official production TCP endpoints, reviewed 2026-10-10.
 * Source: telegramdesktop/tdesktop, Telegram/SourceFiles/mtproto/mtproto_dc_options.cpp
 * Optional `assets/dc.json` can refresh the list without a Play update (hint only; falls back on parse errors).
 */
object MtProtoEndpoints {
    private const val TAG = "TgWatch"

    val bundled = listOf(
        ProbeEndpoint("MTProto DC1", ProbeGroup.MTPROTO, "149.154.175.50"),
        ProbeEndpoint("MTProto DC2", ProbeGroup.MTPROTO, "149.154.167.51"),
        ProbeEndpoint("MTProto DC3", ProbeGroup.MTPROTO, "149.154.175.100"),
        ProbeEndpoint("MTProto DC4", ProbeGroup.MTPROTO, "149.154.167.91"),
        ProbeEndpoint("MTProto DC5", ProbeGroup.MTPROTO, "149.154.171.5"),
    )

    @Volatile
    private var loaded: List<ProbeEndpoint>? = null

    /** Prefer asset override when present and valid; otherwise bundled IPs. */
    fun active(): List<ProbeEndpoint> = loaded ?: bundled

    fun loadFromAssets(ctx: Context) {
        try {
            ctx.assets.open("dc.json").use { stream ->
                val text = stream.readBytes().toString(Charsets.UTF_8)
                val root = JSONObject(text)
                val arr = root.optJSONArray("tcp") ?: JSONArray()
                val parsed = ArrayList<ProbeEndpoint>(arr.length())
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i) ?: continue
                    val host = row.optString("ip").trim()
                    val name = row.optString("name", "MTProto").trim().ifEmpty { "MTProto" }
                    if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
                        parsed += ProbeEndpoint(name, ProbeGroup.MTPROTO, host)
                    }
                }
                if (parsed.isNotEmpty()) {
                    loaded = parsed
                    Log.i(TAG, "Loaded ${parsed.size} MTProto endpoints from dc.json")
                }
            }
        } catch (_: Exception) {
            // Missing or invalid asset is fine — bundled list remains authoritative.
            loaded = null
        }
    }
}
