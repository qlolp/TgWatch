package ru.tgwatch

/** Official production TCP endpoints, reviewed 2026-10-10.
 * Source: telegramdesktop/tdesktop, Telegram/SourceFiles/mtproto/mtproto_dc_options.cpp
 * Keep this list reviewable and update it from that source in releases. WebSocket/HTTPS
 * hostnames are not interchangeable with the abridged TCP transport used by MtProto.
 */
object MtProtoEndpoints {
    val all = listOf(
        ProbeEndpoint("MTProto DC1", ProbeGroup.MTPROTO, "149.154.175.50"),
        ProbeEndpoint("MTProto DC2", ProbeGroup.MTPROTO, "149.154.167.51"),
        ProbeEndpoint("MTProto DC3", ProbeGroup.MTPROTO, "149.154.175.100"),
        ProbeEndpoint("MTProto DC4", ProbeGroup.MTPROTO, "149.154.167.91"),
        ProbeEndpoint("MTProto DC5", ProbeGroup.MTPROTO, "149.154.171.5"),
    )
}
