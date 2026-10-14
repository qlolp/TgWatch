# F-Droid packaging candidate

`metadata/ru.tgwatch.yml` is a disabled candidate recipe for the existing
`ru.tgwatch` application. It has not been submitted to F-Droid, accepted,
or built by F-Droid. The `v1.17.0` source tag must exist on main before its
build can be enabled. The project is MIT licensed and has no proprietary
application SDK; repository inclusion and source/dependency review remain
F-Droid's decisions.

## Source build

The source pins Gradle 8.10.2, Android Gradle Plugin 8.7.3, Kotlin 2.0.21,
JDK 17, compile/target SDK 35, and detekt 1.23.8. The checked-in
`version.properties` is the version authority: 1.17.0 maps to Android code
1017000 (`major * 1000000 + minor * 1000 + patch`). Minor/patch are 0..999.
CI run numbers do not affect APK versions. Release assembly needs no signing
credentials; absent `TGWATCH_KEYSTORE_PATH` it produces an unsigned APK.

A packager should install SDK platform 35 and pinned build tools 35.0.0,
leave signing variables unset, and run from the tag's repository root:

```sh
bash ./gradlew assembleRelease --no-daemon
```

The output is `app/build/outputs/apk/release/app-release-unsigned.apk`.
The F-Droid Gradle recipe selects the app's release variant. In a separate
fdroiddata checkout, copy the candidate metadata, verify the tag and code,
remove the build's `disable` field only after reviewing it, then run the
installed fdroidserver's `fdroid readmeta`, `fdroid lint ru.tgwatch`, and
`fdroid build --test ru.tgwatch:1017000`. These commands are preparation for
review; a local Android build does not establish F-Droid compatibility.
Automatic update discovery is deliberately disabled because the Gradle code
is computed from a properties file; a reviewed recipe must teach the update
checker that mapping before enabling it.

## Store text

Title, short/full descriptions and per-version changelogs live in
`fastlane/metadata/android/{en-US,ru-RU}/`, which F-Droid reads from the source
tree. A changelog file is named after the computed version code, for example
`changelogs/1013002.txt` for 1.13.2 (at most 500 characters). The candidate
recipe's `Summary` matches the English short description; a script test keeps
both, the length limits and the current changelog in place.

## Signing and updates

Normal F-Droid builds use F-Droid's own signing key. They cannot update the
existing production APK signed with certificate SHA-256
`ab74727a44f59684045e7db4c820be309f886433a18a3c06f3439417f3acbef1`.
Android requires matching signing identities even when versionCode increases.
Switching between independently signed packages requires uninstalling and
installing again, with an encrypted portable backup exported beforehand and
restored afterward. Do not export automation tokens: they stay private to the
installation and must be regenerated after migration.

Preserving the upstream signature through F-Droid would require its supported
reproducible-build workflow, upstream signed APK verification, and proof that
an independent unsigned source build matches the upstream payload. No such
proof or distribution approval is claimed here. No signing secrets are part
of the metadata. Existing GitHub release tags and assets remain immutable.

## Analyzer baseline

Detekt runs the pinned default rules plus `config/detekt/detekt.yml` against
main Kotlin source. `config/detekt/baseline.xml` records reviewed legacy
findings, so passing analysis means no unbaselined finding under those rules;
it does not mean all source meets the style guide. Tests and Gradle scripts
are outside this analyzer's configured source set. Review any baseline change
and avoid regenerating it to hide new findings.

## Review references

- [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [Build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Reproducible builds and upstream signatures](https://f-droid.org/docs/Reproducible_Builds/)
- [Official fdroidserver metadata implementation mirror](https://github.com/f-droid/fdroidserver/blob/master/fdroidserver/metadata.py)

The 1.13.2 candidate passed `fdroid readmeta` and `fdroid lint ru.tgwatch` using
fdroidserver 2.4.5 and category names from the official fdroiddata mirror.
Field names/types were also checked against the official fdroidserver source
mirror. F-Droid's documentation website was inaccessible from this cloud
network; no F-Droid build or submission has been performed.
