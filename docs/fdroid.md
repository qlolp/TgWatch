# F-Droid packaging candidate

Current candidate is1.20.1/code1020001/tagv1.20.1; its exact-tag packaging verification is pending.
The1.20.0 results below are retained for the unpublished immutable source snapshot
and do not claim that1.20.0 was published.

`metadata/ru.tgwatch.yml` is a disabled phone-only candidate for1.20.0,
code1020000, tag `v1.20.0`, source
`d38ebb0e60ec872807df145d81cf583d53271d71`. The separate Wear application is not
included. The candidate has not been submitted to or accepted by the catalog.

## Exact-tag local verification

On19October2026, isolated fdroidserver2.4.5 completed normal `readmeta`,
`lint ru.tgwatch` and one exact-tag recipe build:

```sh
fdroid readmeta
fdroid lint ru.tgwatch
fdroid build --test --stop ru.tgwatch:1020000
```

Only the owned staging recipe was enabled. Normal source scanning and signing-config
cleanup ran; neither `--force` nor `--skip-scan` was used. The repository recipe
remains disabled for maintainer review. Automatic update discovery stays disabled
until a maintainer approves the properties-to-versionCode mapping.

The recipe APK is byte-identical to a separate clean Git-aware source assembly.
Both report `ru.tgwatch`,1.20.0/code1020000/min26/target36. Unsigned SHA256:
`90c9d1d8f666e459c90cd47d34c882a7e20428bf72ce3c226fb3f25c9eaf4559`.

Production payload comparison is pending the signed publication. This local recipe
result is separate from F-Droid's official server validation or catalog acceptance.

## Toolchain and assembly

Pins: Gradle8.14.6, AGP8.13.2, Kotlin2.4.21, JDK17, phone compile/target36,
build-tools36.0.0 and detekt1.23.8. Wear retains compile/target35 independently.
`version.properties` is the authority; code is major*1000000+minor*1000+patch.
No CI-run override is accepted. The phone includes Kotlin stdlib, AndroidX and
MIT first-party `:probe-core`, with separate resolved inventories/SBOMs for both apps.

Without signing variables, at the exact source tag:

```sh
bash ./gradlew :app:assembleRelease :app:dependencyInventory :app:generateReleaseSbom --no-daemon
```

The output is `app/build/outputs/apk/release/app-release-unsigned.apk`.
The candidate's `subdir: app` selects the phone; root settings retain shared core.

The pip fdroidserver package omits gradlew-fdroid. Staging used its supported
`gradle` configuration pointing to verified Gradle8.14.6. Normal
`common.read_config()` and `common.getvcs(...).gotorevision('v1.20.0')` prepared VCS
before its early SOURCE_DATE_EPOCH lookup. Product sources and scanner rules were
unchanged. Category keys came from the official fdroiddata mirror, SHA256
`edf68d8d70073fe52a52c6a33f490399ce9dc884acff3a761a3a1e34acf9d541`.

The first-party JAR uses canonical0644/0755 ZIP modes. Actual clean builds under
umasks022/077 are compared by `scripts/check-core-reproducibility.sh`, using
in-process compilers so a reused daemon cannot hide permission changes.

## Signing and distribution

Production certificate SHA256 remains
`ab74727a44f59684045e7db4c820be309f886433a18a3c06f3439417f3acbef1`.
Normal F-Droid signing uses a different key and cannot update that APK in place.
Export an encrypted backup before switching channels; uninstalling removes private
data. Restoring leaves goals disabled for review. Private endpoints, conditions and
tokens are excluded and must be configured again.

Upstream-signature distribution needs the supported reproducible-build/apksigcopier
workflow plus an exact payload match. A recipe build alone does not establish this.
No signing secret belongs in the recipe.

See [submission status](distribution/submission.md). Historical1.19.1 evidence remains
in [its immutable verification record](distribution/verification-1.19.1.json).

## References

- [Inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [Build metadata](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Reproducible builds](https://f-droid.org/docs/Reproducible_Builds/)
- [fdroidserver](https://github.com/f-droid/fdroidserver)
- [fdroiddata mirror](https://github.com/f-droid/fdroiddata)
