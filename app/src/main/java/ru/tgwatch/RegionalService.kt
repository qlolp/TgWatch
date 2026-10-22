package ru.tgwatch

/** Fixed public profiles, separate from historical TargetIds and their history/backup contracts. */
enum class RegionalService(val wire: String, val title: String, val address: String,
    val canonicalHosts: Set<String>) {
    YOUTUBE("youtube", "YouTube", "https://www.youtube.com/", setOf("www.youtube.com", "youtube.com")),
    GEMINI("gemini", "Gemini", "https://gemini.google.com/", setOf("gemini.google.com")),
    NOTEBOOK("notebook", "Notebook", "https://notebook.google.com/",
        setOf("notebook.google.com", "notebooklm.google.com")),
    ANTIGRAVITY("antigravity", "Antigravity", "https://antigravity.google/",
        setOf("antigravity.google", "antigravity.google.com")),
    DOTS("dots", "ChatGPT / dots", "https://chatgpt.com/dots/home", setOf("chatgpt.com"));

    companion object {
        fun fromWire(wire: String): RegionalService? = entries.firstOrNull { it.wire == wire }
    }
}
