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
                ctx.renderString(R.string.copy_oem_tips_xiaomi_redmi_poco_in)
            listOf("samsung").any { m.contains(it) || brand.contains(it) } ->
                ctx.renderString(R.string.copy_oem_tips_samsung_settings_apps_tg)
            listOf("huawei", "honor").any { m.contains(it) || brand.contains(it) } ->
                ctx.renderString(R.string.copy_oem_tips_huawei_honor_settings_battery)
            listOf("oppo", "realme", "oneplus").any { m.contains(it) || brand.contains(it) } ->
                ctx.renderString(R.string.copy_oem_tips_oppo_realme_oneplus_allow)
            listOf("vivo", "iqoo").any { m.contains(it) || brand.contains(it) } ->
                ctx.renderString(R.string.copy_oem_tips_vivo_iqoo_in_the)
            listOf("meizu").any { m.contains(it) || brand.contains(it) } ->
                ctx.renderString(R.string.copy_oem_tips_meizu_turn_off_power)
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
