# Progress — plan: 2026-10-11-next-improvements.md

Started from current main e3d5348 after preserving previous branch history.
User requested autonomous implementation of reviewed recommendations.

| Tasks | Shared boundary | Pre-flight decision |
| --- | --- | --- |
| 1/2 | targetSDK/insets | Gradle owner sets35; UI owner verifies insets |
| 1/3 | automation navigation | new Activity owns UI; MainActivity adds one hook |
| 1/4 | local diagnostic export | exporter owns SAF callback; MainActivity delegates |
| 3/4 | request check | running-only command, serialized with stop/restore |
| 2/5 | versions/docs | release owner changes scripts; root final docs |
| all | manifest/Gradle/git | root manifest/git; build owner Gradle; serialize builds |

Tasks1..6 self-consistency checked against spec; no contradictory ownership.
Design decision: retain native UI, bounded workers and current history storage;
large product changes and external telemetry are deferred because the accepted
review identified a separate scope or recovery/security implications.

## Implementation and review

All implementation tasks1..5 are committed in a1656bb. Current main's release
policy fix (PR13/b95e63d) is reconciled in merge6da94f9, retaining the verified
historical build identity and adding explicit upstream-intent regression tests.
Draft PR15 is open; no main merge or production release has been performed.

Independent review identified one important issue: a future summary-delivery
timestamp suppressed deliveries on earlier, different calendar days. A real
Android regression reproduced it; the guard was corrected to same-local-day
deduplication. Follow-up review covers this fix, API26-safe themes, night
contrast, extracted Activity helpers and the reconciled release tests.

## Fresh validation

200 JVM tests, Detekt, lint (zero errors/19 warnings), debug/release assemblies,
SBOM/inventory and 97 release/reporting/audit/signing helper tests passed. Live OSV covered
23 resolved versions without findings; Trivy and combined audit evidence passed.
fdroidserver2.4.5 readmeta/lint passed for the disabled candidate metadata.

Full local Android35 XML records42 passed/one permission assumption skip/one
clipboard-focus failure from Launcher3/SystemUI ANR overlays; the emulator was
recovered and the unchanged automation class passed4/4. The future-date
summary regression and actual redacted SAF file export passed. Initial CI build
and full Android35 smoke passed; API29 exposed JUnit reflection of API33
LocaleManager in Kotlin synthetic lambda signatures. A private guarded helper
removes those signatures without disabling the six-test UI class. Fresh compile
and javap passed. The matrix retest f1871ed passed both full smoke jobs:
Android29 XML44tests/41passed/3skips; Android35 XML44tests/43passed/1skip;
neither has failures/errors. SDK-specific unsupported checks use explicit skips.
The build failed only UseSdkSuppress for the helper's RequiresApi annotation;
it now uses SdkSuppress. Standalone UI also exposed the notification permission
dialog after a fresh install; its fixture now grants and verifies permission.
The locale/inset fixtures now await and assert monitor teardown before Activity
launch, removing their dependence on an earlier test having stopped the service.
Safe result annotations/smoke exit-status reporting passed14 helper regressions.
The final full source build/lint passed and the focused Android35 UI replay
passed all six tests with no skips, failures or errors. Fresh helper discovery
passed93 tests, plus the four audit/signing checks. Exact-head CI is the final
gate tracked in PR15's checks; earlier successful matrix results are not a
substitute for that head's outcome.
Focused UI and separate notification-denied tests passed. Final outcome belongs in
[the release verification report](../../releases/1.13.0-verification.md).

APK version/signature/hash were checked. Reusable install/start instructions
were saved for cloud review, including pinned Build Tools35.0.0 and serialized
Gradle/emulator operations. Publication and real-phone validation remain separate.
