# Regional Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The user requested sequential execution.

**Goal:** Complete six ordered improvements: regional history, fresh A/B comparisons, factual explanations, distinct verification layers, native Mac menu UI and one-shot change watches.

**Architecture:** Pure bounded policies/codecs feed the existing Android foreground service and native resource-based screens. One fenced repository worker persists device-local data. An AppKit menu app consumes an opt-in private JSON mode of the existing bounded Python engine; public CLI/Android target contracts remain compatible.

**Tech Stack:** Kotlin/native Android, existing JUnit/instrumentation, Python standard library, Node browser fixtures, Swift/Foundation/AppKit/UserNotifications/ServiceManagement.

**Spec:** `docs/superpowers/specs/2026-10-22-regional-experience-design.md`

## Global Constraints

- Android min26/target36; Wear target35; preserve existing four TargetIds, portable backup formats and automation/webhook contracts.
- Fixed five optional regional services; no account access, cookies, page export, raw response/IP storage, automatic VPN switching or new telemetry.
- Current evidence must match active network/configuration; historical evidence never becomes current after restart.
- Native Views, 48dp controls, insets/scrolling, resource-based RU/EN, 200% font; bounded background I/O.
- Device-local regional files stay outside portable backups/diagnostics; restore clears them and fences queued writes.
- Seven-day journal, 1,000 rows, 512KiB; ten comparison pairs; labels≤40 control-free characters; 75s collector deadline; verification TTL15min; one-shot watch TTL24h and confirmation≥15s.
- Next release1.23.0/code1023000, existing production key and all current publication gates; do not rewrite published tags/assets.

## Review Focus

- A late probe or queued disk write after restore/disable/network replacement must not recreate current evidence or deleted history (tasks1/2/4/6).
- A partial A/B run or unrelated country lookup must not be called a better working route or an IP-only diagnosis (tasks1/2/3).
- User-reported/browser observations must never enter automatic public-site consensus or become current after restart (task4).
- Buffered/malformed child output after a Mac process replacement must not update the menu or trigger a notification (task5/6).
- Quiet hours, blocked permission/channel and expiry must leave a watch pending only while fresh valid evidence exists, never consume it silently or deliver stale proof (task6).

---

### Task 1: Durable regional event history

**Files:** Create `RegionalJournal.kt`, `RegionalJournalStorage.kt`, `RegionalRepository.kt`, `RegionalContext.kt`, `RegionalHistoryActivity.kt`; modify `RegionalMonitor.kt`, `EgressCountryUi.kt`, `RegionalServicesActivity.kt`, `TargetRestore.kt`, manifest and both `regional.xml`; tests `RegionalJournalTest.kt`, `RegionalJournalStorageTest.kt`, `RegionalExperienceIntegrationTest.kt`.

**Interfaces:** Produces validated `RegionalJournalEntry`, `RegionalJournalCodec.encode/decode`, `RegionalJournalStorage.read/write/append`, `RegionalRepository.record/snapshot/clear` and `RegionalContext.current/invalidate`. Context contains a random token, route enum and fresh optional country/time. Historical public events carry fixed status/failure/code/confirmation only.

- [x] Write codec/storage tests: roundtrip and consecutive dedup; retention/cap; invalid enums/country/scope/oversize/truncation; failed replacement preserves old file; no raw input fields. Android tests cover reopen, network token change, clear/restore fence and source rendering.
- [x] Run tests; observe missing-feature RED before implementing the bounded codec/storage/repository and history UI.
- [x] Implement observer/context hooks without main-thread file access; restore clears/fences device-local files.
- [x] Run pure tests plus Android integration via CI; expected PASS and unchanged old histories/backup behavior. Commit task1 and ledger evidence.

### Task 2: Fresh manual A/B comparison

**Files:** Create `RegionalComparison.kt`, `RegionalComparisonStorage.kt`, `RegionalComparisonActivity.kt`; modify repository, regional screen, manifest/resources; tests `RegionalComparisonTest.kt`, integration suite.

**Interfaces:** Consumes task1 context and public snapshots. Produces `RegionalComparisonCollector(startElapsed, context, services)` with `offer`, `finish` and invalidation; immutable `RegionalComparisonSnapshot`/`RegionalComparisonPair`; `RegionalComparisonStorage.read/write` and bounded repository comparison operations.

- [x] Write tests rejecting pre-start/wrong-scope observations, changed config, disabled profiles, reversed time and invalid labels; deadline returns missing observations explicitly; same context is identified; ten-pair storage roundtrip/rejection.
- [x] Run RED; implement fresh request/collector and A→switch-yourself→B screen, save/read comparisons off main thread.
- [x] Test that stale cached status cannot satisfy capture, switching during capture cancels it, process recreation cannot complete pending work and partial sides remain unmeasured. Run task suite; expected PASS. Commit task2 and ledger evidence.

### Task 3: Typed “Why?” explanations

**Files:** Create `RegionalExplanation.kt`, `RegionalExplanationUi.kt`; modify regional screen/resources; tests `RegionalExplanationTest.kt`, Android resource assertions.

**Interfaces:** Consumes `RegionalResult`; produces fixed semantic explanation/action IDs with no raw strings. Actions open fixed browser, comparison or feature instructions.

- [x] Write assertions for all statuses and DNS/TLS/timeout failures; auth/403/451/unknown cannot become regional proof; an unreached network stage remains unmeasured.
- [x] Run RED; implement pure mapping and localized dialogs with observed age/source and next action.
- [x] Run pure mapping and exact RU/EN native output tests; expected PASS. Commit task3 and ledger evidence.

### Task 4: Distinct browser and feature verification

**Files:** Create `RegionalVerification.kt`, `RegionalVerificationUi.kt`; modify journal/repository/screen/resources; tests `RegionalVerificationTest.kt`, integration suite; retain existing browser-helper contract.

**Interfaces:** Consumes current opaque context, produces validated layer/outcome records and a `current(context, now)` predicate. Public/browser-reported/user-feature remain separate; journal is historical persistence only.

- [x] Write tests for supported layer/outcome pairs, 15min boundary, context replacement, restart, clock rollback, clear and absence of public-consensus mutation.
- [x] Run RED; implement explicit report dialogs and separate source rows, storing fixed outcomes only.
- [x] Verify opening the browser creates no success record, historical notes cannot become current and RU/EN/large-font UI remains usable. Run task suite; expected PASS. Commit task4 and ledger evidence.

### Task 5: Native Mac menu-bar companion

**Files:** Create `macos/TgWatch/RegionalMenuModel.swift`, `macos/TgWatch/main.swift`, `macos/TgWatch/Info.plist`, `macos/tests/RegionalMenuModelTests.swift`, `scripts/build-mac-app.sh`, `tools/Build-TgWatch-Mac.command`, `docs/macos.md` and Mac CI; modify `scripts/regional-services.py`, Python tests and docs.

**Interfaces:** Existing public `safe_report`/`--json` remains unchanged. New `--app-json` emits schema1 fixed fields including `region_confirmed` and random context ID, never internal route/IP hashes. `RegionalMenuModel` validates bounded lines with process-generation and age checks; AppKit owns menu state, native notifications and opt-in login registration.

- [x] Write Python IPC/termination tests and Foundation model tests: valid five-profile data, malformed/oversize/unknown fields, stale/future timestamps, replaced process, scope change and no credentials/hash fields. Run RED.
- [x] Implement app-only IPC and graceful child cleanup; implement native menu and interpreter discovery without shell commands; explicit notifications/login controls and missing-runtime help.
- [x] Build real `.app` with local Swift compiler, validate plist/bundled engine/ad-hoc signature and run model/process tests. Add pinned Mac CI compilation/test job without weakening Android release gates; CI artifact contains local-build distribution and signing/runtime documentation.
- [x] Run full Python/browser suite and Mac build/tests; expected PASS. Commit task5 and ledger evidence.

### Task 6: One-shot public-response change watches and release

**Files:** Create `RegionalChangeWatch.kt`, `RegionalWatchPreferences.kt`, watch UI; modify monitor/notifications/resources, native Mac model/menu and IPC scheduling; pure/Android/Python/Swift tests; version, changelog, release notes/store metadata.

**Interfaces:** Consumes current public observations and opaque context; `RegionalChangeWatch.observe/ready/delivered` requires two WEB_REACHABLE observations≥15s after arming in one scope. Armed intent persists, positive evidence does not. Existing alert policy remains independent.

- [x] Write RED tests for positive confirmation, network/config reset, unknown/auth/CAPTCHA/DNS rejection, 24h expiry, time reversal, restore/disable/reboot and process-death reset. Native/Android permission/quiet/maintenance tests must not consume blocked delivery.
- [x] Implement opt-in arm/cancel/state, shared confirmation scheduling, dedicated truthful positive notification and durable consumption after successful posting. Mirror the goal in native Mac model/notification menu; requests remain independent per service.
- [x] Run all relevant pure, Python/Node, Swift and Android integration tests; expected PASS. Prepare1.23.0/code1023000 matching metadata/docs, preserve signing/upgrade gates. Commit task6 and ledger evidence.
- [ ] Complete one fresh whole-branch review using spec/plan/ledger. Reproduce important findings RED→GREEN and run green suite; record any minor limitations.
- [ ] Complete full CI, merge reviewed tree under existing user authorization, publish matching immutable tag through existing signing pipeline. Download phone/Wear APKs to verify hashes/certificate/version; independently verify both signed-upgrade reports. Report actual Mac build/interactive/notarization limits accurately.
