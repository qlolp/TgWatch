package ru.tgwatch

/** Fixed public labels; endpoint text can never become a title or an integration payload. */
object TargetNames {
    fun text(id: TargetId): TextValue = TextValue(when (id) {
        TargetId.TELEGRAM -> TextKey.TARGET_TELEGRAM
        TargetId.MATRIX -> TextKey.TARGET_MATRIX
        TargetId.WHATSAPP -> TextKey.TARGET_WHATSAPP
        TargetId.CUSTOM -> TextKey.TARGET_CUSTOM
    })
    fun label(ctx: android.content.Context, id: TargetId): String = AndroidText.resolve(ctx, text(id))
}
