package ru.tgwatch

/** Fixed public labels; endpoint text can never become a title or an integration payload. */
object TargetNames {
    fun label(id: TargetId): String = when (id) {
        TargetId.TELEGRAM -> "Telegram"
        TargetId.MATRIX -> "Matrix"
        TargetId.WHATSAPP -> Lang.t("WhatsApp (веб)", "WhatsApp (web)")
        TargetId.CUSTOM -> Lang.t("Свой HTTPS", "Custom HTTPS")
    }
}
