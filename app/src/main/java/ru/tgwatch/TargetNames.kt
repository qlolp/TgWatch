package ru.tgwatch

/** Fixed public labels; endpoint text can never become a title or an integration payload. */
object TargetNames {
    fun text(id: TargetId): TextValue = when (id) {
        TargetId.TELEGRAM -> TextValue(TextKey.TARGET_TELEGRAM)
        TargetId.MATRIX -> TextValue(TextKey.TARGET_MATRIX)
        TargetId.WHATSAPP -> TextValue(TextKey.TARGET_WHATSAPP)
        TargetId.CUSTOM -> TextValue(TextKey.TARGET_CUSTOM)
        else -> textOf(TextKey.TARGET_CUSTOM_NUMBERED, TextArgument.Number(id.customSlot.toLong()))
    }
    fun label(ctx: android.content.Context, id: TargetId): String = AndroidText.resolve(ctx, text(id))
}
