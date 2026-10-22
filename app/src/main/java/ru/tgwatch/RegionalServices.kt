package ru.tgwatch

/**
 * Opt-in HTTPS probes for consumer services that may be regionally restricted.
 *
 * Outcomes never change Telegram status, latency, widget or webhook delivery.
 */
object RegionalServices {
    enum class Id { YOUTUBE, GEMINI, NOTEBOOK_LM, CHATGPT }

    data class Service(val id: Id, val url: String, val labelRes: Int, val prefKey: String)

    val all = listOf(
        Service(Id.YOUTUBE, "https://www.youtube.com/", R.string.regional_youtube, "regional_youtube"),
        Service(Id.GEMINI, "https://gemini.google.com/", R.string.regional_gemini, "regional_gemini"),
        Service(Id.NOTEBOOK_LM, "https://notebooklm.google.com/", R.string.regional_notebook_lm,
            "regional_notebook_lm"),
        Service(Id.CHATGPT, "https://chatgpt.com/", R.string.regional_chatgpt, "regional_chatgpt"),
    )

    fun service(id: Id): Service = all.first { it.id == id }

    fun outcomeKey(id: Id): String = "regional_outcome_${id.name.lowercase()}"
}
