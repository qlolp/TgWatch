# Distribution preparation: 1.19.0

This package is prepared for review. It is **not an F-Droid submission or catalog
acceptance**. Intended source tag: `v1.19.0`; phone code1019000, package `ru.tgwatch`,
minSdk26/target35, MIT. Release/source/signature checks remain mandatory before
publishing or enabling the disabled candidate recipe.

| Check | Evidence / status |
| --- | --- |
| Candidate metadata | `metadata/ru.tgwatch.yml`, disabled pending exact tag and catalog build |
| Store text | English/Russian fastlane text and code1019000 changelogs |
| Toolchain | Gradle8.14.6, AGP8.13.2, Kotlin2.4.21, JDK17, SDK35/build-tools35.0.0 |
| fdroidserver | Isolated2.4.5; readmeta and lint exit0 on17October2026 |
| Categories | Official fdroiddata keys; source hash recorded in `../fdroid.md` |
| Independent unsigned source build | Pending final committed source snapshot |
| F-Droid recipe build | Pending verification of intended published source tag and catalog tooling |
| Production payload reproducibility | Pending independent source build and exact signed upstream APK comparison |
| GitHub metadata | Prepared values in repository-metadata.json; API access currently blockedCONNECT403 |
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
