package ru.tgwatch

/** UI language for text built in code. Set from resources (R.string.lang) so it matches Android's own resource resolution; JVM tests keep the Russian default. */
object Lang {
    @Volatile var ru: Boolean = true
    fun t(ru: String, en: String): String = if (this.ru) ru else en
    fun update(ctx: android.content.Context) { ru = ctx.getString(R.string.lang) == "ru" }
}
