# F-Droid packaging candidate

`metadata/ru.tgwatch.yml` is a **disabled phone-only candidate** for source version
1.19.1, Android code1019001, tag `v1.19.1` (`e4c75d53712738f1b8ad3c2b42b8891ea82208c9`). It has not been submitted or
accepted by F-Droid. The exact-tag local recipe build passed; the candidate remains disabled for maintainer review.
The separate `ru.tgwatch.wear` APK is not part of this catalog candidate.

## Toolchain and source assembly

The source pins Gradle8.14.6, AGP8.13.2, Kotlin2.4.21, JDK17, compile/target SDK35,
build-tools35.0.0 and detekt1.23.8. `version.properties` is the version authority:
`major * 1000000 + minor * 1000 + patch`; CI run numbers do not affect versions.
The phone uses Kotlin stdlib, AndroidX and the MIT first-party `:probe-core` module.
The exact resolved runtime/test inventories and hashed runtime SBOMs are published
separately for both applications. No proprietary application SDK, Google Play
Services, telemetry or author backend is included.

With the signing variables unset, run at the verified source revision:

```sh
bash ./gradlew :app:assembleRelease :app:dependencyInventory :app:generateReleaseSbom --no-daemon
```

The unsigned APK is `app/build/outputs/apk/release/app-release-unsigned.apk`.
The candidate's `subdir: app` selects the phone while the root settings retain the
shared JVM core. F-Droid may require its normal Gradle/toolchain provisioning;
a source assembly alone does not prove that its isolated build recipe succeeds.

## Metadata verification

On17October2026 the updated candidate passed `fdroid readmeta` and
`fdroid lint ru.tgwatch`, exit0, with isolated **fdroidserver2.4.5**. Validation used
category keys from the official fdroiddata GitHub mirror's `config/categories.yml`,
SHA-256 `edf68d8d70073fe52a52c6a33f490399ce9dc884acff3a761a3a1e34acf9d541`.
The standalone server's older default categories reject `Connectivity`; the staging
configuration uses the actual catalog keys, without disabling any metadata checks.

In a separate fdroiddata checkout, copy the candidate and the source fastlane
metadata. Verify the tag/commit/code, review the recipe's `disable` reason, then run:

```sh
fdroid readmeta
fdroid lint ru.tgwatch
fdroid build --test ru.tgwatch:1019001
```

The local recipe build passed with the candidate enabled only in owned staging,
without `--force` or `--skip-scan`. The repository candidate remains disabled pending
maintainer review. Automatic update discovery is also disabled; a maintainer-approved
recipe must teach the checker the properties-to-versionCode mapping.
See [submission status and evidence](distribution/submission.md) for completed and
pending steps. A successful metadata lint is not a successful F-Droid build.

## Exact-tag local build

`fdroid build --test --stop ru.tgwatch:1019001` completed one successful build from
`e4c75d53712738f1b8ad3c2b42b8891ea82208c9`, with normal source scanning and signing-config
cleanup. Its unsigned APK was byte-identical to a separate clean Git-aware source
assembly: SHA-256 `c297ae84fbaf90eb88fe0cf6180c35a13a9cab94b040842e53e71e07502cfcdb`.
Package/version/API identity was checked with aapt2. This is a local packaging result,
not a build on F-Droid's official server or catalog acceptance.

The pip distribution of fdroidserver2.4.5 lacks its default `gradlew-fdroid` launcher;
staging used the supported `gradle` configuration to the verified Gradle8.14.6 binary.
For a fresh build directory its SOURCE_DATE_EPOCH lookup ran before cloning; normal
`common.read_config()` and `common.getvcs(...).gotorevision('v1.19.1')` preparation
resolved that tool prerequisite. Product sources and scanner rules were unchanged.

The first-party JAR has canonical0644 file/0755 directory ZIP permissions. The real
`scripts/check-core-reproducibility.sh` compares clean builds under umasks022/077,
using in-process compilers so a reused Kotlin daemon cannot hide permission changes.

## Signing and reproducibility

The GitHub production certificate SHA-256 remains
`ab74727a44f59684045e7db4c820be309f886433a18a3c06f3439417f3acbef1`.
Normal F-Droid builds use a different signing key and cannot update that APK in
place. Before changing channels, export an encrypted portable backup; uninstalling
removes private data. Restore on the new installation and review disabled targets.
Tokens and private addresses are not portable and must be configured again.

An independent unsigned source build and a byte comparison with an upstream
production-signed APK are separate checks. Upstream-signature distribution needs
F-Droid's supported reproducible-build/apksigcopier workflow and an exact payload
match. No signing secret is included in the recipe, and no independent production
reproducibility or catalog acceptance is claimed without that evidence.

## References

- [Inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [Build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Reproducible builds](https://f-droid.org/docs/Reproducible_Builds/)
- [fdroidserver source](https://github.com/f-droid/fdroidserver)
- [fdroiddata mirror](https://github.com/f-droid/fdroiddata)

## Production comparison result

For this exact tag, apksigcopier1.1.1 `compare --unsigned` returned0 against the
published production APK for both the independent source assembly and the recipe
build. Phone APK SHA-256:
`b5c7bd423cdf61a0f9bc02d506e26e29886a1a1a89bcc397cfc118a3626a21fd`.
The production certificate matches the documented identity. This is evidence for
the current tag, not catalog acceptance or a promise about future builds.
[Full verification record](distribution/verification-1.19.1.json).
