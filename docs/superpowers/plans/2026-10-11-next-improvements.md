# Implementation plan: TgWatch 1.13.0

Spec: ../specs/2026-10-11-next-improvements-design.md
Base: e3d5348. User authorized autonomous implementation; execute in this isolated
cloud checkout on codex/tgwatch-next-improvements, without another worktree.

## Task 1: UI and Android 15

Own MainActivity, chart, resources/themes/layouts, Lang, and UI-focused tests.
Audit merged localization and selected app locale; fix actual untranslated or
incorrect-language user output. Add dynamic chart/status descriptions and
accessible layout/touch/contrast behavior. Apply idempotent API35 insets.
Use existing instrumentation for meaningful regressions, add locale/insets/font
checks. Do not rewrite UI to Compose. Integrate Task 3/4 hooks after they exist.

## Task 2: build, versions, style and F-Droid

Own Gradle files, workflow, version.properties, release scripts/tests,
.editorconfig, analyzer configuration, F-Droid metadata/docs.
First add tests rejecting malformed SemVer, mismatched tags, off-main tags,
non-monotonic publication and unknown override inputs. Implement deterministic
code 1013000/name1.13.0 and exact tag-triggered release policy preserving all
audit/signing/upgrade gates. Set targetSdk35. Add pinned Kotlin analysis with an
explicit current-code baseline and execute it. Prepare honest F-Droid packaging
instructions and metadata, including separate signing/update limitations.

## Task 3: automation

Own new AutomationPolicy, AutomationSettings, AutomationReceiver and
AutomationSettingsActivity files/tests plus docs/automation.md. Root owns
manifest integration. Implement disabled-by-default 256-bit token credentials
in ordinary private storage outside backup; constant-time authorization,
bounded input/result validation and monotonic 10-second CHECK_NOW throttling.
GET_STATE returns status, checked_at_ms, stale, running, and API version.
CHECK_NOW never enables/restarts disabled monitoring, including restore/stop
races. Provide in-app enable/regenerate/copy UI. Tests first for auth, invalid
actions, rate-limit clock rollback, token reset and stopped-monitor behavior.
Expose Activity.open(ctx), receiver action/extra constants and an internal
running-check helper requirement for root/Task4 integration.

## Task 4: diagnostics, scheduling and notifications

Own MonitorService and new diagnostic/scheduling/export units/tests.
Implement LocalDiagnostics.export(Activity), bounded redacted JSON through SAF;
root/UI agent adds navigation. Diagnostic formatter tests must prove private
history/log/settings fields are excluded. Add monotonic scheduled-arrival
metrics and tests excluding manual/overlap triggers; no false Doze attribution.
Add native progress indicator from truthful daily availability; unknown-only
data hides indicator. Add a running-only check request entry point needed by
Task3, preserving stop/restore and worker ownership semantics.

## Task 5: daily summary review and integration

Root audits DailySummaryReceiver/Prefs/EventNotifications/language startup,
fixing confirmed gaps with tests. Root owns manifest, README, CONTRIBUTING,
CHANGELOG, release notes, final integration and APK/report/PR creation.
Verify Task1 navigation to Task3/4 without file-ownership conflicts.

## Task 6: validation and independent review

Serialize Gradle commands with flock /tmp/tgwatch-next-gradle.lock. Run complete
JVM/helper suites, analyzer, lint, debug/release/SBOM; inspect test counts.
Run Android35 instrumentation and meaningful UI/automation/smoke assertions;
use CI Android29/35 checks for full platform matrix. Review entire branch with
a fresh agent, fix important findings, then commit/push and create PR. Deliver
debug APK/link plus exact verification and any publication blockers.

## Shared interfaces / review focus

Tasks1/3/4 share only navigation hooks, integrated after independent files exist.
Tasks2/1 coordinate targetSDK35/insets. Tasks3/4 coordinate running-only check
requests; no implicit foreground-service resurrection is allowed.
Root manifest merges component declarations after implementations exist.
Probe cancellation, Direct Boot, staged backup replay, 1.12 daily-summary
settings portability, off-main/malformed tags, capability-token secrecy,
locale changes and large-font/inset regressions are required review cases.
