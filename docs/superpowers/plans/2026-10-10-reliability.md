# TG Watch 1.7 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement task-by-task.

**Goal:** Implement the approved reliability review and usability improvements.
**Architecture:** Pure Kotlin probe and timeline components, Android adapters for service/storage/UI. Keep platform views and no account credentials.
**Tech Stack:** Kotlin 2.0.21, Android API 26–35, JUnit 4, Gradle.
**Spec:** ../specs/2026-10-10-reliability.md

## Global Constraints
- Android 8+, local data only; no Telegram login or message sending.
- Never publish debug APK as production. Missing signing secrets block release only.
- UNKNOWN is not downtime. Sparse observations do not bridge gaps.

## Review Focus
- A slow/cancelled probe must not starve future probes.
- Direct Boot migration must merge data without deleting an unreadable source.
- Clock/network changes must not manufacture reliable observations.
- Week exports must contain unknown periods and ISO timestamps.
- APK upgrades must retain package identity, signature and data.

### Task 1: Probe and service health
Files: NetworkProbe.kt, MtProto.kt, MonitorService.kt, WatchdogReceiver.kt; ReliabilityTest.kt.
Interfaces: ProbeOutcome(endpoint, group, reachable, latencyMs, detail); NetworkProbe.check(): ProbeReport; ProbeReport.status; ServiceHealth.isOverdue(lastProgress, now, expectedSec).
- [x] Add failing tests: unavailable first controls with a fast last control; pending controls yield UNKNOWN; wrong MTProto nonce rejected; a stale heartbeat triggers recovery.
- [x] Run testDebugUnitTest in branch CI; inspect failure.
- [x] Implement bounded parallel probe runner, MTProto greeting, per-check network snapshot and independent status expiry.
- [x] Run tests; require success.

### Task 2: Timeline and persistence
Files: Timeline.kt, HistoryStorage.kt, History.kt; ReliabilityTest.kt, HistoryStorageTest.kt.
Interfaces: Observation(at, until, kind, latencyMs); Timeline.stats(observations, from, until); History.stats(ctx, days), History.exportCsv(ctx, days).
- [x] Test 50 minutes OK / 10 bad = 83.33%; unobserved gap breaks outage; seven-day cutoff; truncated history ignored per-line; migration merges uniquely.
- [x] Implement bounded observation coverage, atomic storage, safe Direct Boot migration and legacy minute chart compatibility.
- [x] Run unit suite; require success.

### Task 3: UI and power profiles
Files: PowerProfile.kt, Prefs.kt, MainActivity.kt, ChartView.kt, StatusTileService.kt, StatusWidget.kt, activity_main.xml.
- [x] Test 60/300/60, 30/120/30, 15/60/10 profile intervals.
- [x] Add profile selector, diagnostics, day/week history, ACTION_CREATE_DOCUMENT CSV export; render PARTIAL/UNKNOWN consistently.
- [x] Run tests and lint; require no errors.

### Task 4: Release and end-to-end checks
Files: build.gradle.kts, .github/workflows/build-apk.yml, androidTest, scripts/android-smoke.sh, README.md.
- [x] Implement persistent signing, version increments, immutable release tags and read-only PR checks.
- [ ] Provision production signing Secrets (owner operation; see release dependency below).
- [x] Add emulator lifecycle/network/upgrade checks and unit/lint artifacts.
- [ ] Run CI, inspect logs and fix failures.
- [ ] Run fresh-context branch review; resolve important findings and rerun affected checks.
- [ ] Open PR with verification and signing readiness evidence.

## Release dependency
The original published 1.6 debug signing key has not been recovered. CI checks an actual
1.6 → 1.7 upgrade using a common test key, but does not prove compatibility with the published APK.
Production signing code and a secure bootstrap script are implemented; GitHub signing Secrets
must be provisioned by the owner. No release is published or installed by this task. Deleting an
existing installation would lose app data and is not an approved migration path here.

## Review follow-up
A fresh independent review identified endpoint starvation, blocking cancellation cleanup,
clock rollback, legacy-read failure, weak smoke assertions, and stale PARTIAL rendering.
Regression tests were committed first and CI confirmed their failure. Fixes now retain one
active operation per endpoint, bound asynchronous cleanup, preserve separate clock epochs,
isolate migration failures, and check fresh observations and logs across lifecycle transitions.
Final CI and PR links are recorded in the pull request.
