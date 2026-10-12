# Release-only keep rules. Components are referenced by the platform from the manifest.
-keep class ru.tgwatch.TgWatchApplication { *; }
-keep class ru.tgwatch.MainActivity { *; }
-keep class ru.tgwatch.AutomationSettingsActivity { *; }
-keep class ru.tgwatch.MonitorService { *; }
-keep class ru.tgwatch.StatusTileService { *; }
-keep class ru.tgwatch.StatusWidget { *; }
-keep class ru.tgwatch.WidgetClickReceiver { *; }
-keep class ru.tgwatch.BootReceiver { *; }
-keep class ru.tgwatch.WatchdogReceiver { *; }
-keep class ru.tgwatch.DailySummaryReceiver { *; }
-keep class ru.tgwatch.AutomationReceiver { *; }

# Enum names are persisted in SharedPreferences and restored with valueOf().
-keep enum ru.tgwatch.** { *; }
