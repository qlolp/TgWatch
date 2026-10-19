# Distribution preparation:1.20.0

Current candidate is1.20.1/code1020001/tagv1.20.1; its exact-tag packaging verification is pending.
The1.20.0 results below are retained for the unpublished immutable source snapshot
and do not claim that1.20.0 was published.

Phone-only packaging candidate: `ru.tgwatch`,1.20.0/code1020000/min26/target36,
MIT, source tag `v1.20.0` at `d38ebb0e60ec872807df145d81cf583d53271d71`.
This is preparation for maintainer review; no F-Droid submission has been sent.

| Check | Evidence/status |
|---|---|
| Candidate | `metadata/ru.tgwatch.yml`, disabled pending maintainer review |
| Store text | English/Russian fastlane metadata and code1020000 changelogs |
| Toolchain | Gradle8.14.6, AGP8.13.2, Kotlin2.4.21, JDK17, phone SDK36/build-tools36.0.0 |
| Metadata | Isolated fdroidserver2.4.5 readmeta/lint exit0,19October2026 |
| Source assembly | Clean exact-tag Git-aware unsigned APK succeeded |
| Recipe | Normal exact-tag build succeeded; scanner enabled, no force/skip |
| Unsigned reproducibility | Recipe and independent APKs are byte-identical |
| Production payload comparison | Pending signed publication |
| Repository description/topics | Prepared; GitHub API writes return403 Resource not accessible by integration |
| Official F-Droid build/acceptance | Not performed; candidate remains disabled |

Unsigned SHA256:
`90c9d1d8f666e459c90cd47d34c882a7e20428bf72ce3c226fb3f25c9eaf4559`.
See [F-Droid tool/VCS/category details](../fdroid.md).

The app uses one user-controlled foreground service, independent optional goals,
local history and no author backend or telemetry. Doze/vendor restrictions can
delay checks; coverage and freshness remain visible. Automation and HTTPS webhooks
start disabled. Private addresses, conditions and credentials stay outside portable
backups and public diagnostic/share exports. Wear has a separate package and its
own release files; it is outside this candidate.

Normal F-Droid signing differs from the upstream certificate
`ab74727a44f59684045e7db4c820be309f886433a18a3c06f3439417f3acbef1`.
An in-place channel switch requires compatible signatures. Export an encrypted
backup before uninstalling; restore leaves goals disabled and excludes private
addresses/conditions/tokens. Upstream-signature reproducibility is recorded only
after actual apksigcopier comparison.

## Prepared submission text

Destination, after a separate instruction to send: an
[fdroiddata merge request](https://gitlab.com/fdroid/fdroiddata/-/merge_requests)
or [packaging request](https://gitlab.com/fdroid/rfp/-/issues), following current
maintainer instructions.

Title: **New app: TgWatch — private local reachability monitoring**.

Text: TgWatch is an MIT Android8+ reachability monitor with local history,
English/Russian UI, encrypted portable backups and independent optional Matrix,
WhatsApp Web and custom HTTPS goals. Bounded unauthenticated probes distinguish
unmeasured gaps from outages. Optional automation/integrations start disabled.
Please review the phone-only metadata, immutable source/version reference,
toolchain/dependency evidence, foreground-service/exact-alarm behavior and local
recipe/reproducibility results. No production signing secret is included.

Attach the recipe, exact tag/commit, LICENSE, store metadata/screenshots,
readmeta/lint/build logs, unsigned identity, inventory/SBOM and actual signed-payload
comparison evidence. Exclude real histories, private addresses, conditions and tokens.

Historical [1.19.1 verification](verification-1.19.1.json) is retained unchanged.
