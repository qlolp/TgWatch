# Regional Services Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, with one fresh whole-branch reviewer at the end.

**Goal:** Add truthful optional regional-service warnings and current exit-country information, repair baseline regressions, and deliver a separately measured Mac companion.

**Architecture:** Keep the four historical TargetIds and their archive/automation contracts intact. Add a small regional policy/transport/controller subsystem attached to the existing foreground service, with a dedicated Android activity. The Mac CLI and browser helper use matching public result semantics and shared behavioral fixtures.

**Tech Stack:** Existing Kotlin/native Android/JUnit, Python standard library, dependency-free JavaScript; no new production library.

**Spec:** `docs/superpowers/specs/2026-10-22-regional-services-design.md`.

## Global constraints

- Android min26/target36, Wear min30/target35; preserve signing identity, existing histories, backups, Direct Boot and per-target opt-ins.
- Public response cap 32 KiB; connect/read timeout 3000 ms; intervals 300/600 seconds; confirmation spacing 15000 ms; notification cooldown 300000 ms.
- Cookies/tokens/passwords/raw bodies/IPs are not persisted or exported; public checks cannot certify account functionality.
- No new baseline detekt IDs, waived tests, disabled release gates or unrelated refactoring.

## Review focus

- A VPN/network/configuration change during a slow lookup must invalidate publication and alert consensus, including disable/enable ABA transitions.
- Static bundles, quoted error examples, CAPTCHA, generic 403/451 and sign-in redirects must not become confirmed IP geoblocks.
- Regional-only monitoring must remain alive without resurrecting old targets, and restore/process teardown must close work before clearing state.
- Quiet hours, blocked channels, repeated incidents and clock rollback must not generate duplicate or stale notifications.
- The Mac's measured path and optional browser-session observation must remain explicitly separate from phone state; output must contain no raw responses or credentials.

### Task 1: Restore a green baseline

Files: `BackupRestoreIntegrationTest.kt`, `LocalDiagnosticsIntegrationTest.kt`, `activity_main.xml` and regression assertions in `UiAccessibilityTest.kt`.
- [ ] Preserve CI39718776675 failure evidence; obtain exact assertion messages from the Android report.
- [ ] Fix the backup fixture's explicit retention value and schema5 expectation; replace the cramped horizontal retention group with accessible wrapping/vertical controls.
- [ ] Run the unchanged Android assertions in CI and complete unit/lint/detekt gates; commit.

### Task 2: Fresh country state

Files: `EgressCountry.kt`, `EgressCountryUi.kt`, `CountryLookupState.kt`, `EgressCountryTest.kt`, `CountryLookupStateTest.kt`, main layout/resources and Android integration test.
Produces: a current-network country snapshot and generation-bound lookup ticket; manual refresh and localized freshness presentation.
- [ ] Write and run RED cases for unknown ISO, rollback/expiration, network change and an out-of-order completion after a forced refresh.
- [ ] Implement the policy/coordinator; retain bounded Cloudflare transport, add source/country/age and an accessible refresh control.
- [ ] Run all pure/Android tests, verify no old-network flag appears current; commit.

### Task 3: Regional policy and public transport

Files: `RegionalService.kt`, `RegionalResult.kt`, `RegionalClassifier.kt`, `RegionalConsensus.kt`, `RegionalHttpProbe.kt` and matching unit tests/fixtures.
Produces: seven safe categories, fixed endpoints, bounded response parsing and per-network/per-profile consensus; consumes country only as context.
- [ ] RED: explicit region HTML/JSON accepted; scripts/comments/generic403/451/challenge/auth/rate limits do not confirm region; incomplete/oversized JSON rejected.
- [ ] Implement fixed profile classification, bounded TLS transport with no cookies or arbitrary redirect following, shared secondary permits and cancellation.
- [ ] RED→GREEN: two observations 15000 ms apart, network/config changes, cooldown and network failure without invented recovery; run full suite and commit.

### Task 4: Android screen, lifecycle and warnings

Files: `RegionalPreferences.kt`, `RegionalMonitor.kt`, `RegionalNotifications.kt`, `RegionalServicesActivity.kt`, `MonitorService.kt`, `TargetActions.kt`, `TargetServiceCommands.kt`, `TargetRestore.kt`, manifest/resources and Android integration tests.
Consumes: Task3 results/consensus; Task2 current country. Produces: independent opt-ins, manual checks, same-service background monitoring and local warnings.
- [ ] Add failing Android tests for regional-only lifecycle, stopping Telegram while a regional profile remains enabled, restore disable/clear, 200%-font controls, quiet/blocked warnings and stale task rejection.
- [ ] Implement the dedicated screen and monitoring hooks; keep existing TargetId/history/automation schemas intact; reset regional state at the existing restore boundary.
- [ ] Run API26/29/35/36 integration/smoke and the complete JVM/Python/lint/detekt suite; commit.

### Task 5: Mac companion and authenticated-session helper

Files: `scripts/regional-services.py`, `scripts/tests/test_regional_services.py`, `tools/regional-browser-check.js`, cross-language fixtures and `docs/regional-services.md`.
- [ ] RED: safe classification, bounded HTTP/redirects/timeouts, network-change confirmation reset, cooldown, JSON redaction and CLI failure handling.
- [ ] Implement one-shot/watch CLI, safe optional local macOS alerts and a browser bookmarklet that only classifies visible text locally.
- [ ] Run the full Python suite and browser-helper fixture tests; document manual session checks and why public website access is not playback/inference/dots readiness; commit.

### Task 6: Review and deliver

- [ ] Update version to1.22.0, README/CHANGELOG/release notes, companion instructions and actual validation evidence.
- [ ] Obtain one fresh whole-branch review; grade findings by user impact, fix Important/Critical with RED→GREEN coverage and a green full suite.
- [ ] Merge only the reviewed green tree; publish with the existing permanent key and all dependency/signature/real-upgrade gates. Verify published APK version/hash/certificate and signed upgrade reports.
