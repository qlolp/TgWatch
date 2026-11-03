# Complete iPhone Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task.

**Goal:** Deliver six approved monitoring improvements across iPhone, widgets, Watch and encrypted transfer.
**Architecture:** Core pure models for diagnosis/comparison/backup; SwiftUI feature views; app-group public snapshots and WatchConnectivity companion.
**Tech Stack:** SwiftUI, Foundation, CryptoKit/CommonCrypto, WidgetKit, WatchConnectivity, Swift Testing.
**Spec:** docs/superpowers/specs/2026-11-01-ios-complete-experience.md

## Global Constraints
Minimum iOS 17. No direct pbxproj edits. No VPN configuration changes. English/Russian. Never portray stale data as current. No server/account. Password 12–256 characters; PBKDF2 600000; plaintext cap 32 MiB.

## Review Focus
- Route/lifecycle changes during VPN comparison invalidate an incomplete sample.
- Expired observations in widgets/Watch must not stay green.
- Wrong-password/tampered/path-traversal backups must leave existing data untouched.
- Removing favorites/services must propagate to companion surfaces.
- Personal-team App Group signing must be reported accurately.

### Task 1: Diagnosis and VPN comparison
Files: core ConnectionDiagnosis.swift and tests; Features/VPNComparisonView.swift; MonitorView.swift.
Interfaces: diagnosis(snapshot:fresh:)->reason; VPN comparison receives two fresh independent samples.
- [ ] Tests for down-with-control, partial, offline, stale and latency comparison.
- [ ] Implement models and explicit two-step UI with cancellation/in-memory samples.
- [ ] Run core tests and Xcode build; commit.

### Task 2: Favorites and public snapshot
Files: Features/ServiceCatalog.swift, ServicesView.swift, CompanionSnapshot.swift, FavoritesView.swift.
Interfaces: favoriteIDs, toggleFavorite(id:), latest public snapshot without private data.
- [ ] Persist valid service IDs, compact sortable dashboard and service links.
- [ ] Tests for expiry and privacy allowlist; build; commit.

### Task 3: Widget and Apple Watch
Files: new Xcode widget and watch targets; Features/CompanionBridge.swift; shared public DTO source.
- [ ] Add targets/entitlements through Xcode tools and implement stale-aware widget timeline.
- [ ] Implement WatchConnectivity snapshot delivery and explicit refresh availability.
- [ ] Build both simulator targets and check UI; verify phone signing; commit.

### Task 4: Encrypted backup
Files: core EncryptedBackup.swift and tests; Features/BackupView.swift; Features/ServiceCatalog.swift and MonitorController.swift.
Interfaces: seal/open authenticated payload; preview validated files; restore only on confirmation.
- [ ] Tests for roundtrip, wrong password, tamper, format/bounds/path validation.
- [ ] Implement password UI, document picker/exporter and atomic staged restore.
- [ ] Verify non-destructive failure and stopped monitors after restore; commit.

### Task 5: Whole-branch verification
- [ ] Run core suite, iPhone UI tests, widget/Watch simulator verification through device-interaction.
- [ ] Fresh reviewer checks branch; address substantive findings and rerun relevant tests.
- [ ] Update docs, push PR, complete CI and report limitations precisely.

## Execution ledger
- Plan: inline implementation chosen under user's standing delegation; no additional approval gate. Existing PR103 merged after all checks passed.
