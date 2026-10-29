# F-Droid packaging candidate

`metadata/ru.tgwatch.yml` tracks the disabled phone-only source candidate
(`version.properties`); currently 1.26.0 / code1026000 / tag `v1.26.0`.
No catalog submission or acceptance is implied. The Wear application is separate
and is not included in this recipe. The last published exact-tag verification
remains 1.25.1 below until a 1.26.0 tag is published and re-verified.

## Last verified published candidate 1.25.1 — exact-tag verification passed

On29October2026, the [actual verification run](https://github.com/qlolp/TgWatch/actions/runs/40830515811)
passed for tag `v1.25.1`, source `b7df1b4564b859fd0862b25498009e10cc367056`.
The verifier revision was `27f7837518295fc3fa3caf5d209d285126f6c04d`, which reuses
the shared SDK37-compatible, fail-closed production-certificate guard.

Normal `readmeta`, `lint` and `fdroid build --test --stop` passed. Source scanning
ran; neither `--force` nor `--skip-scan` was used. Independent and recipe unsigned
APKs were retained and are byte-identical, SHA256
`e3cb78d1448ecf7dd019bdafd2e6cb2e7b606d2643bc38a9f1a87bdeb0ed357c`.
Both passed apksigcopier1.1.1 `compare --unsigned` against the published signed APK,
SHA256 `263cabc45c8c1cc0f462d623395b7545ed0d4e188fe3c999849973680f1b23a1`.
The production certificate remains unchanged.

[verification-1.25.1.json](distribution/verification-1.25.1.json) records the exact
source, verifier, toolchain, retained outputs and distinct checks. Earlier local
pre-tag metadata checks are recorded separately. This was isolated normal recipe
validation, not F-Droid server validation. The recipe stays disabled for maintainer
review; both update modes remain `None`. Nothing was submitted to the catalog.

The manual [F-Droid verification workflow](../.github/workflows/fdroid-verify.yml)
uses a dedicated hash-locked fdroidserver2.4.5/apksigcopier1.1.1 environment. It
retains independent and normal recipe unsigned outputs, checks byte equality,
then compares both payloads with the signed published APK. Normal scanning stays
enabled; only the temporary staging recipe has its `disable` field removed.
Official category definitions are pinned by commit and checksum. The workflow
uploads its actual evidence even on failure and never submits to the catalog.

```sh
gh workflow run fdroid-verify.yml --ref main -f tag=v1.25.1
```

Inspect the exact run's
`evidence.json`, retained APKs and source revision before making that claim.
The previous version's distinct outcomes remain in
[verification-1.24.1.json](distribution/verification-1.24.1.json).

## Candidate v1.24.1 — validation status

On 28 October 2026, the exact tag resolved to
`a32d31cc21cf8bbdb56c3f813d4963d74874091b`. `fdroidserver`2.4.5 `readmeta`
passed. `lint ru.tgwatch` passed in isolated staging with the official
`Connectivity` category name; that staging was not a full `fdroiddata` checkout.
The recipe remains disabled in this repository, and both update modes remain
`None`.

The published phone APK was downloaded from the v1.24.1 GitHub release, its SHA256
was verified against the release checksum asset, and `apksigner verify` passed.
It reports `ru.tgwatch`, 1.24.1/code1024001, min26/target36/compile37. Its SHA256 is
`6dced42b54e878753615f1916c6e3b2d92e01b46ebee5dfe3af75f63e284a50f`.

The first clean independent Gradle and normal `fdroid build --test --stop`
attempts used Gradle9.4.1 and JDK17. The plugin marker for AGP9.2.1 could not
be resolved because that environment could not resolve `dl.google.com`. The
F-Droid build reached source checkout and signing-config cleanup, then stopped
during Gradle cleanup.

A subsequent independent exact-tag assembly succeeded using the same pinned
Gradle9.4.1/JDK17 toolchain and a temporary Aliyun Maven mirror init script for
dependency resolution. `:app:assembleRelease`, `:app:dependencyInventory` and
`:app:generateReleaseSbom` completed with 50 actionable tasks. CycloneDX 1.6
validation passed with 22 release runtime artifacts. The unsigned APK SHA256 was
`914b0057fe029df5a171e6b0453f873ec1fe0fec764c355e2a5b2a105d5f110a`.
This temporary build output was not retained, so it cannot now support a byte or
payload comparison.

The normal F-Droid recipe build has not succeeded. Unsigned byte-comparison and
`apksigcopier compare --unsigned` were not run. The verified published APK and the
successful independent assembly separately do not establish reproducibility.

To complete validation, rerun the exact source tag with the pinned toolchain and
working dependency access, retain both unsigned outputs, and follow the commands
and staging notes in the versioned verification record. Remove `disable` only from
a temporary staging copy; do not use `--force` or `--skip-scan`. Maintainer review
is still required. No materials were sent to `fdroiddata`, and no server validation
or catalog acceptance is claimed.

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

Current pins: Gradle9.4.1, AGP9.2.1, Kotlin2.4.21, JDK17, phone compile37/target36,
build-tools37.0.0 and detekt1.23.8. Wear retains compile/target35 independently.
The earlier exact-tag verification above used the older1.20.1 toolchain; it does not
validate the current recipe.
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
