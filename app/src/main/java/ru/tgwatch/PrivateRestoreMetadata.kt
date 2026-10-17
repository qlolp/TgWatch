package ru.tgwatch

/** Private replay policy is never part of a portable backup or a device-protected marker. */
internal data class PrivateRestoreMetadata(val reviewTargets: Set<TargetId> = TargetId.entries.toSet(),
    val baseGenerationId: String? = null) {
    init { require(baseGenerationId == null || Regex("[a-f0-9]{32}").matches(baseGenerationId)) }
    fun encode(): ByteArray = (reviewTargets.sumOf { 1 shl it.ordinal }.toString() + "\n" +
        (baseGenerationId ?: "")).toByteArray(Charsets.US_ASCII)

    companion object {
        const val MAX_BYTES = 36
        fun decode(bytes: ByteArray): PrivateRestoreMetadata {
            val fields = bytes.toString(Charsets.US_ASCII).split('\n')
            require(fields.size == 2)
            val mask = fields[0].toInt()
            require(mask in 0 until (1 shl TargetId.entries.size) && fields[0] == mask.toString())
            return PrivateRestoreMetadata(TargetId.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet(),
                fields[1].takeIf { it.isNotEmpty() })
        }
    }
}
