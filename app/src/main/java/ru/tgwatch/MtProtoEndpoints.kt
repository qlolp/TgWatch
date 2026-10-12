package ru.tgwatch

/** Official production TCP endpoints, reviewed 2026-10-12.
 * Source: telegramdesktop/tdesktop, Telegram/SourceFiles/mtproto/mtproto_dc_options.cpp
 * (kProductionDcOptions). Both address families are listed: an IPv4-only catalog reports a
 * false outage on IPv6-only networks. Keep this list reviewable and update it from that
 * source in releases. WebSocket/HTTPS hostnames are not interchangeable with the abridged
 * TCP transport used by MtProto.
 */
object MtProtoEndpoints {
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
}
