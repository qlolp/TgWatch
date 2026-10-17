package ru.tgwatch

/** Stable DC identities and the embedded address registry live in the portable core. */
object MtProtoEndpoints {
    val all get() = ru.tgwatch.probe.MtProtoEndpoints.all
    val revision get() = ru.tgwatch.probe.MtProtoEndpoints.revision
    fun idFor(name: String) = ru.tgwatch.probe.MtProtoEndpoints.idFor(name)
}
