package ru.tgwatch

import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.Date

/** Only aggregate episode fields enter an explicitly shared summary. */
internal object IncidentPresentation {
    fun describe(episode: OutageEpisode, locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(), targetId: TargetId = TargetId.TELEGRAM): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", locale).apply { timeZone = zone }
        fun time(at: Long) = format.format(Date(at))
        return buildString {
            append(Lang.t("TG Монитор — эпизод\n", "TG Monitor — incident\n"))
            append(Lang.t("Наблюдаемое начало: ", "Observed onset: ")).append(time(episode.startedAt)).append('\n')
            if (episode.startCensored) append(Lang.t("Начало полностью не наблюдалось\n",
                "The full onset was not observed\n"))
            append(if (episode.recovered) Lang.t("Наблюдаемое восстановление: ", "Observed recovery: ")
                else Lang.t("Восстановление не наблюдалось; граница окна: ",
                    "Recovery was not observed; window boundary: "))
                .append(time(episode.endedAt)).append('\n')
            append(Lang.t("Сбой Telegram: ", "Telegram outage: ")).append(recoveryDurationStr(episode.downMs))
            append(Lang.t("\nЧастичная доступность: ", "\nPartial availability: "))
                .append(recoveryDurationStr(episode.partialMs))
            append(Lang.t("\nНет сети: ", "\nNo internet: ")).append(recoveryDurationStr(episode.offlineMs))
            append(Lang.t("\nНет данных: ", "\nNo data: ")).append(recoveryDurationStr(episode.unknownMs))
            append(Lang.t("\nИнтервалы оценены по проверкам; отсутствие данных не доказывает сбой.",
                "\nIntervals are estimated from checks; missing data does not prove an outage."))
        }.let { if (targetId == TargetId.TELEGRAM) it else it.replace("Telegram", TargetNames.label(targetId)) }
    }
}
