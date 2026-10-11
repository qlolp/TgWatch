# F-Droid readiness

- MIT license, reproducible Gradle build, no proprietary SDKs or tracking
- Store copy under `fastlane/metadata/android/{en-US,ru-RU}/`
- Draft recipe: `metadata/ru.tgwatch.yml`
- Automation and webhooks are opt-in; default probes stay on-device

## Submission checklist

1. Publish a signed release that matches `versionName` / CI `versionCode`.
2. Open an fdroiddata MR adapting `metadata/ru.tgwatch.yml`.
3. Keep `subdir: app` and a plain Gradle build.
4. Attach screenshots when available.
5. Document the production signing story for users migrating from older APKs.
