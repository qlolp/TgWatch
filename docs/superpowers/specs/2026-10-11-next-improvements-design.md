# TgWatch 1.13.0: independent improvements

The user asks to implement everything we can independently from the reviewed
roadmap. Work starts at main e3d5348, including 1.12 English/Russian UI and daily
summaries. Preserve that work, portable backup readers, production signing
identity, Direct Boot, deadlines, and truthful history.

## Outcome

Make the existing Telegram monitor easier to use, automate, diagnose and build.
Use the established native UI and bounded network workers. Validate the actual
Android 15 behavior rather than just changing a SDK integer.

1. Audit English/Russian UI, including background components, selected app
   language, chart text alternatives, large fonts, touch targets, and contrast.
   Move remaining resource-appropriate inline copy into translated resources.
2. Target API 35 and apply system-bar insets without cumulative padding. Keep
   the existing specialUse foreground service and verify start/stop, restore,
   network changes and device lock behavior. Do not claim Play approval.
3. Replace CI-run-based versionCode with a checked-in SemVer version, mapped as
   major * 1,000,000 + minor * 1,000 + patch. Minor/patch are 0..999 and the code
   must fit Android's signed positive integer range. New version is 1.13.0,
   code 1013000. A release tag must exactly match the checked-in version and
   reference a commit reachable from main. Existing signed releases stay intact.
   Preserve audit and certificate/upgrade gates. Add style configuration and an
   honest baseline-based Kotlin analyzer; prepare F-Droid metadata/instructions,
   without submitting or claiming acceptance/reproducibility.
4. An opt-in explicit exported broadcast supports CHECK_NOW and GET_STATE for
   automation clients. A randomly generated 256-bit bearer token authenticates
   every request with constant-time comparison. Token remains outside backups,
   diagnostic exports and Direct Boot storage. No unauthenticated state access,
   monitoring enablement, settings writes, history API or localhost server.
   CHECK_NOW only requests a check from an already running enabled monitor,
   limited to one accepted request per 10 seconds using elapsed time. Return
   bounded ordered-broadcast result bundles with stable documented fields.
   Provide an in-app enable/disable/regenerate/copy UI, disabled by default.
5. Add bounded local diagnostic export using the Storage Access Framework.
   Include app/OS version, permission/power flags, aggregate history, current
   status freshness, endpoint results and scheduling counters. Exclude account,
   IP addresses, identifiers, raw logs/history, settings tokens and passwords.
   Never upload automatically or install external telemetry.
6. Track late scheduled checks with monotonic time, separating manual triggers
   and overlapping requests. Report late arrivals and elapsed delay; record
   whether device idle was observed rather than asserting Doze caused a delay.
   Add a compact daily availability progress indicator to the ongoing
   notification, excluding unknown/offline time consistently with TimeStats.
7. Audit the newly merged daily summaries for missed permission, quiet-hour,
   clock-change and language cases and fix confirmed regressions with tests.

## Architecture and constraints

Automation preferences and policy have their own files, Activity and receiver.
Diagnostic formatting and scheduling accounting are small testable units.
MainActivity only adds navigation/export hooks and accessibility/insets changes.
MonitorService only adds scheduling hooks and the compact notification display.
The bounded NetworkProbe executor, cancellation and transport closing remain.

The earlier reviewed ideas are intentionally not translated into arbitrary new
subsystems: multiple services, remote alerts/reporting, Room, Compose/Glance and
an unsigned endpoint feed require different product boundaries or demonstrated
needs. Extra HMAC alone does not strengthen password encryption. QR secrets
would change recovery semantics. These are documented as decisions, not done.

## Evidence

Run JVM tests, Python release-policy tests, baseline/static analysis, Android
lint, debug/release assembly, exact resolved dependency/SBOM audits, and Android
instrumentation/smoke checks. Add meaningful regression tests for automation
authorization/rate limiting, diagnostic redaction, scheduling accounting,
SemVer/tag policies, locale overrides and SDK35 insets. Build a test-only APK,
preserve production certificates, and create a reviewable PR. Publication to
main/GitHub Releases/F-Droid remains separate from preparing and testing code.
