package ru.tgwatch

import android.os.Build

/** Подсказки по снятию ограничений батареи на разных оболочках. */
object OemTips {

    fun manufacturerHint(): String? {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return when {
            listOf("xiaomi", "redmi", "poco", "blackshark").any { m.contains(it) || brand.contains(it) } ->
                "Xiaomi / Redmi / POCO: в настройках приложения включи «Автозапуск» и в «Контроле активности» выбери «Нет ограничений»."
            listOf("samsung").any { m.contains(it) || brand.contains(it) } ->
                "Samsung: Настройки → Приложения → TG Монитор → Батарея → «Без ограничений». Убери приложение из «Спящих»."
            listOf("huawei", "honor").any { m.contains(it) || brand.contains(it) } ->
                "Huawei / Honor: Настройки → Батарея → Запуск приложений → TG Монитор → «Управлять вручную» и включи все переключатели."
            listOf("oppo", "realme", "oneplus").any { m.contains(it) || brand.contains(it) } ->
                "OPPO / Realme / OnePlus: разреши автозапуск и отключи оптимизацию батареи для TG Монитор в настройках приложения."
            listOf("vivo", "iqoo").any { m.contains(it) || brand.contains(it) } ->
                "vivo / iQOO: в настройках батареи разреши работу в фоне и автозапуск для TG Монитор."
            listOf("meizu").any { m.contains(it) || brand.contains(it) } ->
                "Meizu: отключи энергосбережение для TG Монитор и разреши автозапуск."
            else -> null
        }
    }
}
