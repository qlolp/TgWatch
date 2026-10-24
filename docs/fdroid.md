# F-Droid packaging candidate

`metadata/ru.tgwatch.yml` currently points to the disabled phone-only 1.23.0
candidate, code1023000, tag `v1.23.0`; its recipe has not been validated for that
version. The last exact-tag local verification recorded below applies to 1.20.1,
code1020001, source `4b2aba4d1c87606ed482752cf027c85e5896dece`, and is not evidence
for 1.23.0. The separate Wear application is not included. Neither candidate has
been submitted to or accepted by the catalog.

## Exact-tag local verification

On19October2026, isolated fdroidserver2.4.5 completed normal `readmeta`,
`lint ru.tgwatch` and one exact-tag recipe build:

```sh
fdroid readmeta
fdroid lint ru.tgwatch
fdroid build --test --stop ru.tgwatch:1020001
```

Only the owned staging recipe was enabled. Normal source scanning and signing-config
cleanup ran; neither `--force` nor `--skip-scan` was used. The repository recipe
remains disabled for maintainer review. Automatic update discovery stays disabled
until a maintainer approves the properties-to-versionCode mapping.

The recipe APK is byte-identical to a separate clean Git-aware source assembly.
Both report `ru.tgwatch`,1.20.1/code1020001/min26/target36. Unsigned SHA256:
`c3821b95cc184ea6020ca106242a60b126f3e77e1aefffc8c0f2455fcd507e07`.

Production payload comparison passed: apksigcopier1.1.1 `compare --unsigned`
returned0 for both independent and recipe APKs against the published production
APK, SHA256`497d5541412054467dc7a840d926be4ad60abacac1787f5a34f7675c8ec25021`.
This result is separate from official F-Droid server validation or catalog acceptance.

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
`common.read_config()` and `common.getvcs(...).gotorevision('v1.20.1')` prepared VCS
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

[Published hashes, signer/audit and reproducibility evidence](distribution/verification-1.20.1.json).
