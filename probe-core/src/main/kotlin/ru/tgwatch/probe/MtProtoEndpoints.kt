package ru.tgwatch.probe

/** Official production TCP endpoints, reviewed 2026-10-12.
 * Source: telegramdesktop/tdesktop, Telegram/SourceFiles/mtproto/mtproto_dc_options.cpp
 * (kBuiltInDcs and kBuiltInDcsIPv6). Either address family can confirm reachability.
 * Keep this list reviewable and update it from that source in releases. WebSocket/HTTPS
 * hostnames are not interchangeable with the abridged TCP transport used by MtProto.
 */
object MtProtoEndpoints {
    fun idFor(name: String): String? = Regex("MTProto DC([1-5])( \\(IPv6\\))?").matchEntire(name)?.let {
        "dc${it.groupValues[1]}-v${if (it.groupValues[2].isEmpty()) "4" else "6"}"
    }
    val all = listOf(
        ProbeEndpoint("MTProto DC1", ProbeGroup.MTPROTO, "149.154.175.50"),
        ProbeEndpoint("MTProto DC1 (IPv6)", ProbeGroup.MTPROTO, "2001:0b28:f23d:f001:0000:0000:0000:000a"),
        ProbeEndpoint("MTProto DC2", ProbeGroup.MTPROTO, "149.154.167.51"),
        ProbeEndpoint("MTProto DC2 (IPv6)", ProbeGroup.MTPROTO, "2001:067c:04e8:f002:0000:0000:0000:000a"),
        ProbeEndpoint("MTProto DC3", ProbeGroup.MTPROTO, "149.154.175.100"),
        ProbeEndpoint("MTProto DC3 (IPv6)", ProbeGroup.MTPROTO, "2001:0b28:f23d:f003:0000:0000:0000:000a"),
        ProbeEndpoint("MTProto DC4", ProbeGroup.MTPROTO, "149.154.167.91"),
        ProbeEndpoint("MTProto DC4 (IPv6)", ProbeGroup.MTPROTO, "2001:067c:04e8:f004:0000:0000:0000:000a"),
        ProbeEndpoint("MTProto DC5", ProbeGroup.MTPROTO, "149.154.171.5"),
        ProbeEndpoint("MTProto DC5 (IPv6)", ProbeGroup.MTPROTO, "2001:0b28:f23f:f005:0000:0000:0000:000a"),
    )
    /** Local cohort only: changing any embedded address starts a separate detailed series. */
    val revision: Long = java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256")
        .digest(all.joinToString("\n") { "${idFor(it.name)}:${it.address}" }.toByteArray(Charsets.US_ASCII)))
        .long.and(Long.MAX_VALUE).coerceAtLeast(1L)
}
