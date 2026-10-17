# Distribution preparation: 1.19.1

This package is prepared for review. It is **not an F-Droid submission or catalog
acceptance**. Intended source tag: `v1.19.1`; phone code1019001, package `ru.tgwatch`,
minSdk26/target35, MIT. Release/source/signature checks remain mandatory before
publishing or enabling the disabled candidate recipe.

| Check | Evidence / status |
| --- | --- |
| Candidate metadata | `metadata/ru.tgwatch.yml`, disabled pending exact tag and catalog build |
| Store text | English/Russian fastlane text and code1019001 changelogs |
| Toolchain | Gradle8.14.6, AGP8.13.2, Kotlin2.4.21, JDK17, SDK35/build-tools35.0.0 |
| fdroidserver | Isolated2.4.5; readmeta and lint exit0 on17October2026 |
| Categories | Official fdroiddata keys; source hash recorded in `../fdroid.md` |
| Independent unsigned source build | Pending exact corrected v1.19.1 source tag |
| F-Droid recipe build | Pending verification of intended published source tag and catalog tooling |
| Production payload reproducibility | Pending exact v1.19.1 production-signed comparison |
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

## Independent source assembly evidence

The earlier v1.19.0 source tag 9a5f54fa5eb1088e411f9e7092a47efce249aae8
passed an independent Git-aware build and an actual fdroidserver2.4.5 recipe build.
Those unsigned APKs were byte-identical (SHA-256
29c274b7096fdfe0c9f77a5c1e92613c361b3d8dd810f022a5c6e1400c67893b).
Its production publication stopped before upgrade/publication because the private
signing umask changed ZIP permissions inside the first-party JAR and thus its SBOM
hash. The tag remains immutable and no production APK was published for it.

The corrected v1.19.1 build gives the JAR canonical file/directory permissions.
A real clean-build test with separate in-process compilers verifies byte equality
under umasks022 and077; a reused Kotlin daemon would mask this reproduction.
Exact-tag source/F-Droid/signed-payload checks will be recorded here after that
immutable source and production APK exist. No catalog acceptance is implied.
