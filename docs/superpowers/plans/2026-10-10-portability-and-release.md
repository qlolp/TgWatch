# TgWatch 1.10 Implementation Plan

> For agentic workers: use test-first development and independent file ownership. Coordinator integrates shared Android files and runs a whole-branch review.

**Goal:** implement the authorized review improvements on main4d33b26 and deliver a verified APK.
**Architecture:** pure protocol, time-window, crypto and codec policies; Android adapters for SAF, persistence and widget; existing native service remains.
**Tech Stack:** Kotlin2.0.21,AGP8.7.3,Gradle8.10.2,JDK17,SDK35,JUnit4,system org.json,Java crypto.
**Spec:** ../specs/2026-10-10-portability-and-release-design.md

## Global Constraints
- minSdk26,targetSdk34; no Telegram login/account and no TLS verification bypass.
- Existing settings/history migrate; bounded input, current epoch rules and observation gaps remain honest.
- All source work stays in /workspace/TgWatch on codex/tgwatch-1.10; no remote publication/Secrets changes.
- Agents own disjoint files, do not commit or invoke Gradle concurrently; focused pure tests may run in parallel. Coordinator performs final Gradle and commit/review packaging.
- Preserve source1.2 via verified tar+stash+bundle. No worktree or reset of user changes.

## Review Focus
- Malformed archive/wrong password must leave current data and service unchanged.
- Process death during validated restore must complete pending transaction before monitoring can report a fresh status.
- Clock rollback/restart/expired observation may not invent successful status or completed MTTR episode.
- Older/manual release rerun, partial release and new push must preserve newer latest and existing signing identity.
- Legacy hour prefs/CSV, excessive numeric values and partial records must not silently corrupt settings/time coverage.

## Task1: strict MTProto protocol
Files: MtProto.kt; ReliabilityTest.kt; EndpointFallbackTest.kt; new MtProtoValidationTest.kt.
- [x] Add tests rejecting malformed TL tail, padding/vector/length/nonce/id and framing; witness assertion failures.
- [x] Implement strict bounded parsing; update old fixtures to real resPQ bodies; retain deadlines/cancellation.
- [x] Run pure tests, record command/log and self-review.

## Task2: minute quiet hours and last-success widget
Files: Prefs.kt, ProbeRules.kt, StatusWidget.kt; new QuietHours.kt,LastSuccessStore.kt and policy/tests. MainActivity/MonitorService integration belongs to coordinator.
Interfaces: Prefs.quietStartMinute(ctx),quietEndMinute(ctx),setQuietHoursMinutes(ctx,start,end); LastSuccessStore.record(ctx,statusName,checkedAt),lastSuccessAt(ctx),clear(ctx).
- [x] Tests for23:15–08:45, boundaries/equal endpoints/legacy hour defaults and OK versus PARTIAL/failure timestamps.
- [x] Implement minute policies/migration preserving old APIs; widget displays persistent last success.
- [x] Provide integration lines for parent; focused tests RED→GREEN.

## Task3: MTTR, counts and history adapters
Files: Timeline.kt,History.kt,HistoryStorage.kt; new CsvImport.kt; tests. Own EventLog additions in History.kt.
Interfaces: TimeStats.okChecks,downChecks,partialChecks,offlineChecks,unknownChecks,completedOutageCount,mttrMs; History.snapshot(ctx):List<Observation>,replaceSnapshot(ctx,List<Observation>); EventLog.replace(ctx,List<String>); CsvImport.parse(text):List<Observation>.
- [x] Test completed/gapped/ongoing/clipped/clock-epoch episodes and subtype counts; test CSV validation and legacy UTC compatibility.
- [x] Add fields with defaults and summary; implement snapshot/replace with durable-first cache update; append sync policy60s/status changes.
- [x] Run pure history/statistics tests and report adapters for coordinator.

## Task4: portable authenticated backup
Files: new BackupSnapshot.kt,BackupCodec.kt,BackupCrypto.kt and tests only; Android integration belongs to coordinator.
Interfaces: BackupSnapshot(createdAt:Long,settings:Map<String,Any>,observations:List<Observation>,log:List<String>); BackupCodec.encode(snapshot):ByteArray,decode(bytes):BackupSnapshot; BackupCrypto.encrypt(plaintext,password:CharArray):ByteArray,decrypt(envelope,password:CharArray):ByteArray.
- [x] Test real roundtrip, distinct salts/nonces, wrong password/header/payload tampering/truncation, size/count limits and malformed numeric/settings values.
- [x] Implement exact envelope/AAD/KDF from spec and strict versioned JSON validation. Keep input bounded before expensive work; expose MAX_PLAINTEXT_BYTES and MAX_ENVELOPE_BYTES.
- [x] Run tests with real org.json JVM dependency; report generic failure semantics and no persisted password.

## Task5: safe release, signing initialization and SBOM
Files: .github/workflows/build-apk.yml,app/build.gradle.kts,scripts/configure-signing.sh,scripts/android-smoke.sh; new scripts/release-policy helpers/tests as appropriate.
- [x] Exercise old/new latest, existing release/draft, invalid code and restore/init signing with mocked gh/keytool, without real Secrets changes.
- [x] Separate release concurrency, preserve tags/latest, validate code; explicit init/restore modes; strengthen timestamp history assertion.
- [x] Generate CycloneDX SBOM from resolved releaseRuntimeClasspath with hashes; add Trivy sbom CI gate/artifact. Add testImplementation org.json:json:20240303 for task4 tests.
- [x] Version1.10/code11, keep signed-upgrade verification intact; YAML/shell/tests and real SBOM generation report.

## Task6: Android integration and delivery (coordinator)
Files: MainActivity.kt,MonitorService.kt,AndroidManifest.xml,activity_main.xml; new BackupManager.kt,RestoreTransaction.kt,TgWatchApplication.kt/backup UI helper and tests; README/CHANGELOG/docs/releases1.10/architecture diagram.
- [x] Test validated restore transaction recovery/invalid input and Android adapter state; implement pending replay before components.
- [x] Integrate quiet-hour minutes/last-success recording; add SAF backup/restore/CSV-import preview and bounded off-main work.
- [x] Document use and limitations, add Mermaid map, refresh current release notes/cloud start instructions.
- [x] Run full unit/lint/debug/unsigned-release and Android tests; review all changes; fix material findings; verify/copy APK and report remaining external checks.
