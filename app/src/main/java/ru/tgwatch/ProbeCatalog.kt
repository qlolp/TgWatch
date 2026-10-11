package ru.tgwatch

import android.content.Context

/**
 * Optional extra services beyond Telegram. Telegram probes always run;
 * extras are WEB-group HTTP checks that do not change Telegram status classification
 * but appear in diagnostics and can drive separate webhook labels.
 */
enum class ExtraService(val id: String, val titleRu: String, val titleEn: String) {
    MATRIX("matrix", "Matrix", "Matrix"),
    WHATSAPP("whatsapp", "WhatsApp Web", "WhatsApp Web"),
    CUSTOM("custom", "Свой URL", "Custom URL");

    fun title(): String = Lang.t(titleRu, titleEn)
}

object ProbeCatalog {
    fun telegramEndpoints(): List<ProbeEndpoint> = listOf(
        ProbeEndpoint("api.telegram.org", ProbeGroup.WEB, "https://api.telegram.org/"),
        ProbeEndpoint("web.telegram.org", ProbeGroup.WEB, "https://web.telegram.org/"),
        ProbeEndpoint("core.telegram.org", ProbeGroup.WEB, "https://core.telegram.org/"),
    ) + MtProtoEndpoints.active()

    fun controlEndpoints(): List<ProbeEndpoint> = listOf(
        ProbeEndpoint(Lang.t("Яндекс", "Yandex"), ProbeGroup.CONTROL, "https://ya.ru/"),
        ProbeEndpoint("Mail.ru", ProbeGroup.CONTROL, "https://mail.ru/"),
        ProbeEndpoint("Google 204", ProbeGroup.CONTROL, "https://www.gstatic.com/generate_204"),
    )

    /** Extra HTTP targets; results use ProbeGroup.WEB but are tagged by name prefix. */
    fun extraEndpoints(ctx: Context): List<ProbeEndpoint> {
        val out = mutableListOf<ProbeEndpoint>()
        if (Prefs.extraServiceEnabled(ctx, ExtraService.MATRIX)) {
            out += ProbeEndpoint("Matrix", ProbeGroup.WEB, "https://matrix.org/")
        }
        if (Prefs.extraServiceEnabled(ctx, ExtraService.WHATSAPP)) {
            out += ProbeEndpoint("WhatsApp Web", ProbeGroup.WEB, "https://web.whatsapp.com/")
        }
        if (Prefs.extraServiceEnabled(ctx, ExtraService.CUSTOM)) {
            val url = Prefs.customProbeUrl(ctx).trim()
            if (url.startsWith("https://") && url.length in 12..256) {
                out += ProbeEndpoint("Custom", ProbeGroup.WEB, url)
            }
        }
        return out
    }
}
