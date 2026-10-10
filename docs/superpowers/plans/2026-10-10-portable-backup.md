# Portable backup and event controls implementation plan

Spec: `docs/superpowers/specs/2026-10-10-portable-backup.md`.

Global constraints: preserve existing signing identity, opt-ins, timeline gaps, Direct Boot and 7-day retention; no secrets in git/logs; no CSV import, NTP, accounts or coroutine rewrite. Execute inline under existing authorization.

## Task 1: completed-incident MTTR

Write failing pure timeline cases for two completed episodes, interrupted coverage, clipped start/end, ongoing, PARTIAL/offline and archived epoch. Run the direct Kotlin/JUnit runner (Expected: missing fields then assertion failures). Implement completed count/average and user summary. Rerun the complete logic suite (Expected: all pass). Commit.

Produces: `TimeStats.completedOutages`, `meanRecoveryMs`.

## Task 2: encrypted portable snapshot and restore

Write real crypto/format tests for roundtrip, random ciphertext, wrong password, tampering, limits, unsupported versions and preference validation. Run RED, implement bounded JCA codec and settings schema, run GREEN. Add Android restore tests before wiring: snapshots omit dynamic state, invalid files preserve data, restore persists settings/history/log across restart and clears live status. Restore journal is the atomic authoritative commit; startup applies it before monitor startup and removes it only after all files/preferences commit. Background document-picker UI requests password and confirms replacement while monitor is stopped. Full suite and API29/35 integration (Expected: all pass). Commit.

Produces: `BackupData`, `BackupCodec`, `BackupStore`; consumes Observation and existing Prefs/History. MTTR consumes restored observations without changing its rules.

## Task 3: event-specific notification settings

Write Android tests for legacy fallback, independent flags/sounds, PARTIAL disabled by default, separate channels and quiet/DND silent behavior. Capture RED in CI. Implement preference/UI/service dispatch; retain shared cooldown and old vibration policy. API29/35 tests and complete unit/lint/smoke (Expected: all pass). Commit.

Consumes backup preference schema: include all explicit event settings and legacy fallback values.

## Task 4: dependencies and local key protection

Write Python tests for OSV findings/withdrawals, incomplete responses and empty inventories; run RED, implement exact resolved inventory/SBOM and fail-closed API audit. Add CI gate and report upload. Test key protection with a temporary independent fixture before touching existing backup. Implement secure-input signing configuration and macOS Keychain helper; verify permanent key certificate and fresh-process readback before deleting only its plaintext password sidecar. Expected: identical certificate, encrypted keystore retained, password retrievable privately. Commit scripts/docs.

## Task 5: review and signed publication

Set version 1.10, docs/changelog/release notes. Update upgrade baseline to the immutable published 1.9.40 hash and assert previous opt-ins plus new defaults. Run final CI, request one fresh whole-branch review, fix Important/Critical findings with RED→GREEN tests, merge and publish. Verify release APK hash/version/certificate and signed upgrade report. Expected: all mandatory gates pass and installable APK published.

## Review focus

Check authenticated-container parsing/length arithmetic/CPU bounds, accidental secret retention, restore crash windows and concurrent monitor writes, live-state resurrection/clock epochs, persisted preferences type validation, notification channel user overrides/quiet hours, MTTR boundaries, dependency graph completeness/fail-open scanning, and Keychain recovery without rotating or losing the signing identity.
