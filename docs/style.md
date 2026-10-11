# Style

- `.editorconfig` is the shared formatting baseline (UTF-8, LF, 4-space Kotlin/XML).
- Prefer resource strings over hardcoded UI copy (`values/` Russian default, `values-en/` English).
- Keep `MonitorService` / `MainActivity` changes focused; large files are candidates for a later Compose and coroutine split.
- Optional next step: enable ktlint/detekt in Gradle once a baseline is generated for the existing codebase.
