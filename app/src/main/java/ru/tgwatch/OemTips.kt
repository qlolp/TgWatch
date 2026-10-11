package ru.tgwatch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Подсказки по снятию ограничений батареи на разных оболочках. */
object OemTips {

    fun manufacturerHint(ctx: Context): String? {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return when {
            listOf("xiaomi", "redmi", "poco", "blackshark").any { m.contains(it) || brand.contains(it) } ->
                ctx.getString(R.string.oem_xiaomi)
            listOf("samsung").any { m.contains(it) || brand.contains(it) } ->
                ctx.getString(R.string.oem_samsung)
            listOf("huawei", "honor").any { m.contains(it) || brand.contains(it) } ->
                ctx.getString(R.string.oem_huawei)
            listOf("oppo", "realme", "oneplus").any { m.contains(it) || brand.contains(it) } ->
                ctx.getString(R.string.oem_oppo)
            listOf("vivo", "iqoo").any { m.contains(it) || brand.contains(it) } ->
                ctx.getString(R.string.oem_vivo)
            listOf("meizu").any { m.contains(it) || brand.contains(it) } ->
                ctx.getString(R.string.oem_meizu)
            else -> null
        }
    }

    fun openAppDetails(ctx: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
