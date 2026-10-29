# Reliability Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline. Steps use checkbox syntax; user authorized sequential implementation, cleanup and publication.

**Goal:** Reduce history allocation, stabilize ADB tests, fix profile text and add real TLS broker evidence, protected main and signed1.25.1.
**Architecture:** Existing Kotlin policies/Android adapters; bounded sort-and-deduplicate retention; injectable timing at Python subprocess boundary; isolated real Mosquitto in CI; existing release pipeline.
**Tech Stack:** Kotlin/JUnit/Android instrumentation, Python unittest, JDK17, pinned Docker Mosquitto, Gradle9.4.1/SDK37, GitHub Actions.
**Spec:** `docs/superpowers/specs/2026-10-29-reliability-release-design.md`

## Global Constraints

- Preserve65 000 Telegram rows, epoch metadata, duplicate precedence, input immutability, old formats and atomic/repair behavior.
- Canonical65k host retention allocations <4MiB; no raised heap/disabled native stress to claim a fix.
- Production ADB defaults/deadlines and MQTT certificate/hostname verification remain enforced.
- MQTT test CA is JVM-test-only, localhost only; no personal endpoints, accounts or signing secrets in fixtures.
- Version1.25.1/code1025001, existing Android certificate,12 public assets; all existing gates plus MQTT.
- Physical/notary/catalog acceptance claims require actual evidence; absent access is recorded as a blocker, not success.

## Review Focus

- Duplicate metadata after a real check must not overwrite it; shuffled epochs and latest expired epoch must retain exact old semantics (task1).
- Memory optimization must preserve newest row cap without mutating caller lists, including65001 rows (task1).
- Old/empty/unstable boot cannot pass after a slow command or exhausted deadline; CLI still runs real subprocess (task2).
- MQTT must reject wrong CA, hostname and credentials, with no fake successful delivery or production trust override (task4).
- Branch protection must require available validations while allowing the single owner to use PRs; no admin bypass or publication-key rotation (task5/6).

### Task 1: History retention allocation
**Files:** `HistoryRetention.kt`(new), `Timeline.kt`, `TimelineRetentionTest.kt`(new), `HistoryPreparationAllocationTest.kt`, Gradle isolated history heap task if needed.
**Interfaces:** consumes immutable Observation and cutoff/limit; produces same List<Observation> as retained contract. Timeline.retained delegates without API changes.
- [x] Write literal tests for duplicate/meta/epoch/cap/immutability and canonical65k allocation<4MiB; run old code and observe allocation assertion RED.
- [x] Replace per-row boxed-key map/sorted copies in retention with bounded sorted/grouped traversal and primitive comparator. Run semantic/allocation suite; expected GREEN, print measured bytes.
- [x] Keep original Android65k integration test; add/run isolated heap validation and all relevant JVM tests; commit with evidence.

### Task 2: Deterministic ADB timing
**Files:** `scripts/adb-readiness.py`, `scripts/tests/test_adb_readiness.py`, direct-boot tests only if the same investigated boundary requires it.
**Interfaces:** ensure_ready keeps positional API, optional keyword clock/pause/runner default to real monotonic/sleep/subprocess; CLI inputs unchanged.
- [x] Write controlled-clock tests of old/empty/new/changing boot, per-command and overall deadlines. Show RED for unavailable injection, retain actual CLI tests.
- [x] Inject dependencies at subprocess boundary; move sub-second policy assertions to deterministic time, keep real CLI rejection and hung-process deadline. Mutation ignoring boot-id must fail tests.
- [x] Run full Python suite, expected zero failures; commit.

### Task 3: Localized profile label
**Files:** `TargetLegacySettingsUi.kt`, native text/settings integration tests.
**Interfaces:** profileLabel(Context):String resolves TextValue through existing Context.renderString; no preference changes.
- [x] Add native RU/EN tests for3 profiles and custom; run native old formatter and observe actual TextValue leak RED.
- [ ] Apply resolver, run native regression and existing large-font/settings checks; expected GREEN; commit.

### Task 4: Actual TLS MQTT gate
**Files:** `MqttTlsIntegrationTest.kt`(new), broker fixture shell/Python helper, app Gradle task and build-apk workflow job.
**Interfaces:** production MqttTransport.openTls/submit unchanged; isolated test JVM uses temporary trust store; CI exposes job mqtt-tls and machine-readable actual-test report.
- [x] Write real-broker tests and run without fixture to fail; create pinned Mosquitto TLS/auth fixture and independent subscriber. Prove valid publish and wrong CA/name/password outcomes.
- [x] Add dedicated Gradle Test task, keep normal unit suite offline, make mqtt-tls a release prerequisite and verify nonzero cases/zero skips.
- [ ] Run actual CI broker plus full CI; expected GREEN; commit.

### Task 5: Protection and external validation
**Files:** current validation docs/evidence, disabled F-Droid recipe; repository settings snapshot (private build evidence).
**Interfaces:** consumes available CI context/app IDs; records protection readback and explicit platform evidence/status without secrets.
- [x] Read current protection, device and signing availability. Execute available checks; preserve absent hardware/cert as concrete blockers.
- [ ] Prepare current-version F-Droid recipe staging/readmeta/lint/build with scanner intact and pinned tools; keep actual output, do not claim acceptance. Exact-tag evidence runs once the release tag exists.
- [x] After candidate CI contexts exist, configure strict PR/CI protection with0 human approvals, enforce admins, no force/delete; verify readback. Commit accurate docs.

### Task 6: Final review, release and cleanup
**Files:** version.properties, Mac metadata, CHANGELOG, release1.25.1 notes, store metadata, current roadmap/evidence.
**Interfaces:** same production app IDs/key/assets, signed upgrades1.10.47 and latest stable1.25.0; source SHA/tree tied to checks and release.
- [ ] Prepare1.25.1 metadata and truthful release notes; full suite and one fresh whole-branch review. Fix Important/Critical with actual RED→GREEN; record minors.
- [ ] Verify exact source CI, merge under configured rules, archive proof and clean only own temporary workspace/branch. Publish once through existing workflow; do not overwrite tags/assets.
- [ ] Verify downloaded phone/Wear hashes, certificate, version, signed-upgrade reports; perform available exact-tag F-Droid follow-up and report remaining external blockers honestly.
