# F-Droid packaging candidate

`metadata/ru.tgwatch.yml` is a **disabled phone-only candidate** for source version
1.19.1, Android code1019001, intended tag `v1.19.1`. It has not been submitted or
accepted by F-Droid. Verify the immutable tag on main before enabling the recipe.
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

The build remains disabled until its exact published source and catalog build have
been verified. Automatic update discovery is also disabled; a maintainer-approved
recipe must teach the checker the properties-to-versionCode mapping.
See [submission status and evidence](distribution/submission.md) for completed and
pending steps. A successful metadata lint is not a successful F-Droid build.

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
