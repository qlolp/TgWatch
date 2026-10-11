package ru.tgwatch

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * API 33 locale calls live outside test classes: Kotlin compiles lambdas that capture a
 * LocaleManager into methods whose signatures name it, and JUnit's reflection over such a
 * class fails on older Android before any test can skip itself.
 */
// Android 13+ only: callers must check Build.VERSION.SDK_INT >= 33 first.
object AppLocales {
    private fun manager(ctx: Context) = ctx.getSystemService(LocaleManager::class.java)
    fun get(ctx: Context): String = manager(ctx).applicationLocales.toLanguageTags()
    fun set(ctx: Context, tags: String) { manager(ctx).applicationLocales = LocaleList.forLanguageTags(tags) }
}
