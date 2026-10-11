# F-Droid readiness

The project is prepared for an F-Droid submission:

- MIT license, reproducible Gradle build, no proprietary SDKs
- `fastlane/metadata/android/` descriptions for `en-US` and `ru-RU`
- Draft recipe at `metadata/ru.tgwatch.yml` for [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
- Versioning from git tags (`vMAJOR.MINOR.PATCH` → `versionName` / `versionCode`)

## Submission checklist

1. Publish a signed release tag that matches `versionName` / `versionCode` from `git describe`.
2. Open an fdroiddata MR that copies or adapts `metadata/ru.tgwatch.yml`.
3. Point `commit` at the release tag; keep `subdir: app` and Gradle build.
4. Attach screenshots under `fastlane/metadata/android/<locale>/images/` when available.
5. Confirm the production signing key story for users migrating from older unofficial APKs.

AntiFeatures: none expected (no tracking, no non-free network services beyond probing public Telegram endpoints).
