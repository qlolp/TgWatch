package ru.tgwatch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Подсказки по снятию ограничений батареи на разных оболочках. */
object OemTips {

    fun manufacturerHint(): String? {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return when {
            listOf("xiaomi", "redmi", "poco", "blackshark").any { m.contains(it) || brand.contains(it) } ->
                Lang.t("Xiaomi / Redmi / POCO: в настройках приложения включи «Автозапуск» и в «Контроле активности» выбери «Нет ограничений».", "Xiaomi / Redmi / POCO: in the app settings, turn on “Autostart” and set “Battery saver” to “No restrictions”.")
            listOf("samsung").any { m.contains(it) || brand.contains(it) } ->
                Lang.t("Samsung: Настройки → Приложения → TG Монитор → Батарея → «Без ограничений». Убери приложение из «Спящих».", "Samsung: Settings → Apps → TG Monitor → Battery → “Unrestricted”. Remove the app from “Sleeping apps”.")
            listOf("huawei", "honor").any { m.contains(it) || brand.contains(it) } ->
                Lang.t("Huawei / Honor: Настройки → Батарея → Запуск приложений → TG Монитор → «Управлять вручную» и включи все переключатели.", "Huawei / Honor: Settings → Battery → App launch → TG Monitor → “Manage manually”, then turn on all switches.")
            listOf("oppo", "realme", "oneplus").any { m.contains(it) || brand.contains(it) } ->
                Lang.t("OPPO / Realme / OnePlus: разреши автозапуск и отключи оптимизацию батареи для TG Монитор в настройках приложения.", "OPPO / Realme / OnePlus: allow auto-launch and turn off battery optimization for TG Monitor in the app settings.")
            listOf("vivo", "iqoo").any { m.contains(it) || brand.contains(it) } ->
                Lang.t("vivo / iQOO: в настройках батареи разреши работу в фоне и автозапуск для TG Монитор.", "vivo / iQOO: in the battery settings, allow background activity and auto-start for TG Monitor.")
            listOf("meizu").any { m.contains(it) || brand.contains(it) } ->
                Lang.t("Meizu: отключи энергосбережение для TG Монитор и разреши автозапуск.", "Meizu: turn off power saving for TG Monitor and allow auto-start.")
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
