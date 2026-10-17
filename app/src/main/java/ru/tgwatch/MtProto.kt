package ru.tgwatch

/** Compatibility adapter: the phone and watch execute one shared protocol implementation. */
object MtProto {
    fun request(nonce: ByteArray, nowMillis: Long) = ru.tgwatch.probe.MtProto.request(nonce, nowMillis)
    fun validResponse(payload: ByteArray, nonce: ByteArray) = ru.tgwatch.probe.MtProto.validResponse(payload, nonce)
    fun probe(host: String, socketFactory: () -> java.net.Socket, cancellation: ProbeCancellation) =
        ru.tgwatch.probe.MtProto.probe(host, socketFactory, cancellation)
}
