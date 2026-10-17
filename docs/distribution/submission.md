# Distribution preparation: 1.19.1

This package is prepared for review. It is **not an F-Droid submission or catalog
acceptance**. Verified published source tag: `v1.19.1`; phone code1019001, package `ru.tgwatch`,
minSdk26/target35, MIT. Release/source/signature checks remain mandatory before
publishing or enabling the disabled candidate recipe.

| Check | Evidence / status |
| --- | --- |
| Candidate metadata | `metadata/ru.tgwatch.yml`, disabled pending maintainer review; local exact-tag build verified |
| Store text | English/Russian fastlane text and code1019001 changelogs |
| Toolchain | Gradle8.14.6, AGP8.13.2, Kotlin2.4.21, JDK17, SDK35/build-tools35.0.0 |
| fdroidserver | Isolated2.4.5; readmeta and lint exit0 on17October2026 |
| Categories | Official fdroiddata keys; source hash recorded in `../fdroid.md` |
| Independent unsigned source build | Passed from exact v1.19.1 tag e4c75d53712738f1b8ad3c2b42b8891ea82208c9; identity verified |
| F-Droid recipe build | Local fdroid build --test --stop passed; normal scan enabled, no force/skip |
| Production payload reproducibility | Passed: apksigcopier signature transplant verifies both independent and F-Droid source APKs against published production APK |
| GitHub metadata | Prepared values in repository-metadata.json; REST access denied (Forbidden); existing native Git authentication works |
| Sending a submission | Not sent; requires separate instruction to send |

The runtime includes Kotlin stdlib, AndroidX and first-party `:probe-core`, without
proprietary application SDKs. Release inventories/SBOMs identify actual resolved
artifacts; OSV and Trivy gates reject unavailable or incomplete evidence. The watch
has a separate package and six separate release files; this F-Droid candidate is
phone-only.

Background monitoring uses one user-controlled foreground service. Timers/watchdog
support monitoring under Android restrictions; Doze/vendor restrictions can delay
checks and freshness/coverage are reported. Exact-alarm access is explained and
user controlled. Additional targets, automation and HTTPS webhooks start disabled.
Private endpoints/tokens stay outside portable backups and diagnostic/share exports.
There is no author backend, telemetry, account login or phone/Wear data transport.

Normal F-Droid signing differs from the upstream production certificate
`ab74727a44f59684045e7db4c820be309f886433a18a3c06f3439417f3acbef1`.
Do not promise an in-place channel switch. Export an encrypted backup first;
restoring leaves targets disabled for review and excludes tokens/private addresses.

## Proposed submission, not sent

Recipient: F-Droid packaging maintainers, through a merge request in
[fdroiddata](https://gitlab.com/fdroid/fdroiddata/-/merge_requests), following its
current contribution instructions. Alternatively open an app-packaging request in
[RFP](https://gitlab.com/fdroid/rfp/-/issues) if a build recipe needs maintainer work.

Proposed title: **New app: TgWatch — private local reachability monitoring**.

Proposed text: TgWatch is an MIT-licensed Android8+ reachability monitor with local
history, English/Russian UI, encrypted portable backups and optional independent
Matrix/WhatsApp Web/custom HTTPS targets. It uses bounded unauthenticated protocol
checks and distinguishes unmeasured gaps from outages. Optional integrations are
disabled by default. Please review the attached phone-only metadata, immutable
source/version reference, toolchain and dependency evidence, and foreground/exact
alarm behavior. The candidate is not a claim of upstream-signature reproducibility.

Attach after final verification: the candidate recipe, exact source tag/commit,
LICENSE, store metadata/screenshots, readmeta/lint logs, unsigned build identity and
resolved inventory/SBOM, plus payload-comparison results when available. No signing
key, real history, private endpoints or tokens belong in the submission.

## Independent source assembly evidence

The exact v1.19.1 source commit is
`e4c75d53712738f1b8ad3c2b42b8891ea82208c9`. Independent clean Git-aware assembly
and actual fdroidserver2.4.5 recipe build both passed; their unsigned APKs were
byte-identical, SHA-256
`c297ae84fbaf90eb88fe0cf6180c35a13a9cab94b040842e53e71e07502cfcdb`.
Both report ru.tgwatch1.19.1/code1019001/min26/target35. readmeta/lint exit0.
The F-Droid build ran normal source scanning and signing-config cleanup.

The repository recipe stays disabled for maintainer review; the tested copy was
enabled only in isolated packaging staging. Staging used the supported Gradle path
configuration for pinned8.14.6 and normal VCS initialization before building.
No `--force`, scanner bypass or signing secret was used. The source tarball and
tested recipe are retained with verification evidence.

Tag v1.19.0 remains an unpublished source snapshot after its release SBOM gate
detected umask-dependent ZIP permissions in the first-party JAR. v1.19.1 fixes
those permissions and tests actual clean builds under both masks; code payloads
were unchanged. The immutable older tag was not moved.

Published phone APK SHA-256 is
`b5c7bd423cdf61a0f9bc02d506e26e29886a1a1a89bcc397cfc118a3626a21fd`.
apksigcopier1.1.1 `compare --unsigned` returned0 for both the independent source
APK and the F-Droid recipe APK against that production APK. Its existing production
certificate was verified. This establishes current signed-payload reproducibility;
official F-Droid server validation and catalog acceptance are separate external
steps. No submission has been sent.

[Published artifact hashes and full verification results](verification-1.19.1.json)
include separate phone/Wear identities, audits and the successful signed-upgrade
workflow. All12 official release assets were downloaded and independently checked.
