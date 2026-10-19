package ru.tgwatch

object TargetEndpoints {
    private const val MATRIX_VERSIONS_PATH = "/_matrix/client/versions"
    private const val MAX_ENDPOINT_CHARS = 256
    val controls = listOf(
        ProbeEndpoint("Control Google", ProbeGroup.CONTROL, "https://www.gstatic.com/generate_204"),
        ProbeEndpoint("Control Yandex", ProbeGroup.CONTROL, "https://ya.ru/"),
        ProbeEndpoint("Control Mail", ProbeGroup.CONTROL, "https://mail.ru/"))

    fun resolve(definition: TargetDefinition, raw: String?,
        condition: ru.tgwatch.probe.HttpProbeCondition? = null): ProbeEndpoint? {
        val address = if (raw != null) HttpsUrlPolicy.normalize(raw, MAX_ENDPOINT_CHARS, allowQuery = false)
            else if (definition.requiresEndpoint) null else when (definition.id) {
                TargetId.MATRIX -> ExtraProbes.MATRIX_URL
                TargetId.WHATSAPP -> ExtraProbes.WHATSAPP_URL
                else -> null
            }
        return address?.let { url -> when (definition.id) {
            TargetId.MATRIX -> ProbeEndpoint("Matrix", ProbeGroup.EXTRA,
                if (url.trimEnd('/').endsWith(MATRIX_VERSIONS_PATH)) url.trimEnd('/')
                else url.trimEnd('/') + MATRIX_VERSIONS_PATH, HttpProbeMode.MATRIX_VERSIONS)
            TargetId.WHATSAPP -> ProbeEndpoint("WhatsApp Web", ProbeGroup.EXTRA, ExtraProbes.WHATSAPP_URL)
            TargetId.CUSTOM -> ProbeEndpoint("Custom HTTPS", ProbeGroup.EXTRA, url, HttpProbeMode.STRICT_2XX, condition)
            TargetId.TELEGRAM -> null
        } }
    }
    fun informational(extras: List<ProbeEndpoint>, definitions: List<TargetDefinition>,
        endpoint: (TargetId) -> String?): List<ProbeEndpoint> {
        val active = definitions.filter { it.enabled && it.id != TargetId.TELEGRAM }.mapNotNull { definition ->
            resolve(definition, if (definition.requiresEndpoint) endpoint(definition.id) else null)?.let {
                definition.id to it.address
            }
        }
        return extras.filterNot { extra -> active.any { (id, address) ->
            when (id) {
                TargetId.MATRIX -> extra.address.trimEnd('/') == address.removeSuffix(MATRIX_VERSIONS_PATH).trimEnd('/')
                TargetId.WHATSAPP -> extra.address.trimEnd('/') == address.trimEnd('/')
                else -> extra.address == address
            }
        } }
    }

}
