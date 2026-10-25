package ru.tgwatch

import android.content.Context

/** Device-local maintenance windows per target. Never enter backups or logs. */
internal object MaintenanceWindows {
    private const val SEP_ROWS = ";"
    private const val SEP_FIELDS = "|"
    private const val FIELD_COUNT = 5
    private const val ID_INDEX = 0
    private const val START_INDEX = 1
    private const val END_INDEX = 2
    private const val MASK_INDEX = 3
    private const val ENABLED_INDEX = 4

    fun load(ctx: Context, id: TargetId): List<MaintenanceWindow> =
        load(ctx, key(id))

    fun load(ctx: Context, key: String): List<MaintenanceWindow> =
        Prefs.sp(ctx).getString(key, "").orEmpty().split(SEP_ROWS).mapNotNull(::decode)

    fun save(ctx: Context, id: TargetId, windows: List<MaintenanceWindow>) {
        save(ctx, key(id), windows)
    }

    fun save(ctx: Context, key: String, windows: List<MaintenanceWindow>) {
        require(windows.size <= MaintenanceWindow.MAX_WINDOWS)
        val encoded = windows.joinToString(SEP_ROWS) { encode(it) }
        Prefs.sp(ctx).edit().putString(key, encoded).apply()
    }

    fun isActive(ctx: Context, id: TargetId, now: Long = System.currentTimeMillis()): Boolean =
        try {
            active(load(ctx, id), now)
        } catch (_: Exception) {
            // Context-free JVM fixtures have no SharedPreferences; treat as no windows.
            false
        }

    fun active(windows: List<MaintenanceWindow>, now: Long): Boolean =
        MaintenanceSchedule.activeAt(windows, now)

    fun key(id: TargetId) = "maintenance.${id.wire}"

    private fun encode(window: MaintenanceWindow) =
        listOf(window.id, window.startMinuteOfDay, window.endMinuteOfDay, window.daysMask,
            if (window.enabled) 1 else 0).joinToString(SEP_FIELDS)

    private fun decode(raw: String): MaintenanceWindow? {
        val parts = if (raw.isBlank()) emptyList() else raw.split(SEP_FIELDS)
        if (parts.size != FIELD_COUNT) return null
        return runCatching {
            MaintenanceWindow(parts[ID_INDEX], parts[START_INDEX].toInt(), parts[END_INDEX].toInt(),
                parts[MASK_INDEX].toInt(), parts[ENABLED_INDEX] == "1")
        }.getOrNull()
    }
}
