# TgWatch iPhone MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Получить проверенный в Simulator и на личном iPhone локальный Telegram-monitor с ручными и foreground-проверками, честной свежестью и историей.

**Architecture:** Новый изолированный iOS target использует SwiftUI и локальный Swift package. Существующие Android/Wear и AppKit/Python сборки не изменяются; переносятся смысловые контракты и golden fixtures. Native URLSession/Network transport отделён от чистой классификации, persistence и UI.

**Tech Stack:** Swift, SwiftPM, SwiftUI, Foundation, Network.framework, XCTest/XCUITest, Xcode; без новых third-party runtime dependencies.

**Spec:** [Предлагаемый дизайн](../specs/2026-10-30-iphone-mvp-design.md). **MVP и draft PR утверждены пользователем 2026-10-30. Apple account mutations не разрешены. iPhone 15, iOS 27.2 со слов владельца; Personal Team, без платной membership. Совместимость с установленным Xcode ещё не проверена.**

## Global Constraints

- Minimum iOS **17.0** — предложение, подтвердить по устройству до Task1; точные macOS/Xcode/SDK фиксируются после inventory.
- Один active batch; monotonic deadline **6 секунд**, максимум **4** соединения; HTTP **64 KiB**, MTProto **4096 bytes**.
- Foreground interval **60 секунд**, opt-in и выключен по умолчанию; inactive/background отменяет loop/batch.
- Stale и gap threshold **120 секунд**; retention **7 дней**, максимум **10 000** записей; iOS history schema **1**.
- Unknown gaps не outage; clock rollback создаёт новую epoch; no Telegram account/messages, no secrets/URLs in shared diagnostic logs.
- RU/EN, VoiceOver и Dynamic Type; обычная TLS validation, без ATS exceptions.
- MVP не включает backend/APNs, background tasks, VPN/Network Extension, Mac subprocess, widgets, Android backup import или regional profiles.
- Apple login/enrollment/payment, создание certificates/profiles/App IDs, signing team и upload остаются отдельными явно подтверждаемыми действиями на доверенном Mac.
- Не менять Android/Mac source, версию выпущенного1.27.0, release workflows, signing keys или опубликованные теги/assets.

## Review Focus

- Wi-Fi→cellular/VPN transition во время ответа: callback старой epoch не должен менять UI/историю (Task2/3).
- Неверное время/restore/restart: сохранённый OK не должен выглядеть новым или создавать отрицательные durations (Task3).
- Captive portal/HTML challenge/HTTP redirect: ответ control host не является доказательством Telegram MTProto или отсутствия блокировки (Task1/2).
- Background/force-quit/Low Power Mode: отсутствие наблюдений остаётся UNKNOWN, нет обещания timed background polling (Task3/4).
- Ошибка записи/обрезанный JSON/переполненная история: не терять старую корректную историю и не показывать повреждённые данные как измерения (Task3).

---

## Preconditions and execution boundary

Сначала пользователь/основной агент подтверждает предложенный foreground-MVP и отвечает на вопросы в spec: iPhone/iOS, доступный Mac, Personal Team против TestFlight. До этого выполнять только read-only inventory/доработку документов. Brainstorming gate относится к реализации, не к уже заказанному созданию этого плана.

На доступном Mac собрать read-only `sw_vers`, `xcode-select -p`, `xcodebuild -version`, `xcodebuild -showsdks`, `xcrun simctl list devices available`, `xcrun devicectl list devices`, `df -h`. Если команда требует установки/accept license — записать blocker, не подтверждать автоматически. Не считывать private keys, сертификаты не экспортировать. Записать обезличенные результаты в `docs/ios/toolchain.md`: без UDID/AppleAccount/TeamID.

Выбрать совместимые установленный Xcode и runtime; только затем получить отдельный согласованный install/download шаг для реально отсутствующего компонента. В текущей cloud Linux-среде Xcode и simulator отсутствуют. Нельзя превращать успешную Linux text-check в iOS build evidence.

## File map (future implementation, nothing created yet)

| Файл/директория | Ответственность |
|---|---|
| `ios/TgWatchCore/Package.swift` | Pure policies + Apple transport package, XCTest targets, без внешних dependencies |
| `ios/TgWatchCore/Sources/TgWatchCore/{ProbeModels,ProbeClassifier,MTProtoCodec}.swift` | Typed evidence, классификация, bounded protocol parser |
| `ios/TgWatchCore/Sources/TgWatchCore/{ProbeRunner,HTTPProbe,TCPProbe,AppleProbeEngine}.swift` | Native bounded transport, cancellation, epoch/deadline |
| `ios/TgWatchCore/Sources/TgWatchCore/{HistoryEntry,HistoryStore,HistoryCoverage}.swift` | iOS schema1, atomic writes, limits, observation gaps |
| `ios/TgWatchCore/Tests/TgWatchCoreTests/` | Golden parser/classifier/transport/history tests, explicit fixtures provenance |
| `ios/TgWatchIOS.xcodeproj/`, shared scheme `TgWatchIOS` | Checked-in native app + XCTest/XCUITest project, local package link, no fixed signing identity |
| `ios/TgWatchIOS/{TgWatchApp,MonitorController,MonitorView,HistoryView}.swift` | Scene lifecycle, observable presentation, controls/history |
| `ios/TgWatchIOS/{Info.plist,PrivacyInfo.xcprivacy,en.lproj,ru.lproj}` | Minimal configuration, actual API usage privacy manifest, translated copy |
| `ios/TgWatchIOSTests/`, `ios/TgWatchIOSUITests/` | Lifecycle and real simulator UI tests |
| `scripts/test-ios.sh`, `docs/ios/{README,toolchain,device-testing}.md` | Fail-closed local commands and evidence; no login/install/provision automation |

Project generation via Xcode itself; XcodeGen/CocoaPods/Fastlane unnecessary. Do not import `macos/TgWatch/main.swift` or `RegionalEngineProcess.swift`. This avoids changing released targets merely to share a small amount of Foundation code.

## Task 1: Pure probe contract plus launchable simulator slice (0.5–1.5 days)

**Files:** Create project/package, `ProbeModels.swift`, `ProbeClassifier.swift`, `MTProtoCodec.swift`, `TgWatchApp.swift`, initial `MonitorView.swift`; tests `ProbeClassifierTests.swift`, `MTProtoCodecTests.swift`; `Fixtures/mtproto-v1.json` + provenance README.

**Interfaces:**
- `ProbeStatus: String, Codable` = `ok`, `partial`, `down`, `noNetwork`, `unknown`.
- `ProbeEvidence: Codable, Equatable` contains `mtprotoConfirmed: Bool`, `webConfirmed: Bool`, `controlConfirmed: Bool`, `pathUnavailable: Bool`, `primaryMeasurementsComplete: Bool`.
- `classify(_ evidence: ProbeEvidence) -> ProbeStatus`: pathUnavailable→noNetwork; both Telegram signals→ok; one→partial; neither+incomplete primary measurements→unknown; neither+complete+control→down; otherwise unknown. Cancellation is never passed as a completed measurement.
- `MTProtoCodec.makeRequest(nonce: Data) throws -> Data`, `validateResponse(_ data: Data, nonce: Data) -> Bool`; reject non16-byte nonce and >4096-byte response, enforce current JVM fixture semantics.

- [ ] Write classifier truth-table and malformed/truncated/nonce/padding/fingerprints tests; derive vectors from `probe-core/.../MtProtoFixtures.kt` and `MtProtoValidationTest.kt`. Store literal bytes, origin SHA and expected decisions; do not make a second permissive parser to generate expected values.
- [ ] On Mac run `swift test --package-path ios/TgWatchCore --filter 'ProbeClassifierTests|MTProtoCodecTests'`; record expected initial compilation/assertion failures.
- [ ] Implement only types/classifier/codec and local package linkage. Create native project/shared scheme and a display explicitly marked fixture/demo, with no inferred live status.
- [ ] Rerun pure tests; `xcodebuild -list -project ios/TgWatchIOS.xcodeproj`, then simulator build below. Actual launch must show fixture label, not Telegram uptime. Record toolchain versions and simulator runtime.
- [ ] Commit independently: `feat(ios): add tested probe contracts and simulator app shell`.

## Task 2: Native local network check (2–4 days)

**Files:** Create `ProbeRunner.swift`, `HTTPProbe.swift`, `TCPProbe.swift`, `AppleProbeEngine.swift`, `ProbeTransportTests.swift`, `ProbeEngineTests.swift`, `NetworkFixtures.swift`; wire a manual check in `MonitorController.swift`.

**Interfaces:**
- `ProbeSnapshot` contains `epoch: UInt64`, `startedAt: Date`, `finishedAt: Date`, `evidence: ProbeEvidence`, `status: ProbeStatus`; diagnostics use endpoint names/result codes, not user URLs/tokens.
- `protocol ProbeRunner { func check(epoch: UInt64) async throws -> ProbeSnapshot }`; cancellation throws `CancellationError`, never returns DOWN.
- `AppleProbeEngine: ProbeRunner` owns finite connections and one batch. Inject monotonic clock and transport test doubles. `HTTPProbe.check(url: URL, deadline: ContinuousClock.Instant) async throws -> Bool`; `TCPProbe.check(endpoint: String, nonce: Data, deadline: ContinuousClock.Instant) async throws -> Bool`.

- [ ] Tests first: same classifier outcomes for all golden cases; 65,537-byte HTTP response rejected; 4097-byte TCP response rejected; deadline stops batch within6s+test harness tolerance; 5th transport waits until a slot frees; cancellation closes all transports; late old-epoch result is discarded; TLS errors are never success; incomplete primary measurements plus successful control produce UNKNOWN, not DOWN; control captive portal alone never produces OK.
- [ ] Run `swift test --package-path ios/TgWatchCore --filter 'ProbeTransportTests|ProbeEngineTests'` and capture RED.
- [ ] Implement URLSession ephemeral HTTP sessions with no cookie persistence, запрет auto-follow redirects как у `HttpEndpointProbe`, HEAD→GET только при405/501, HTTP response100…499 как свидетельство ответа web endpoint, не работы аккаунта, default trust, incremental body cap. Implement Network.framework TCP with bounded parser, random16-byte nonce, same target set as source contracts; explain transport response vs trusted service in UI.
- [ ] Implement native path observation only as connection availability/epoch invalidation. NWPath available does not prove internet reachability; VPN presence does not prove a working tunnel. Cancel active tasks on epoch change; acceptance guarded both before and after await.
- [ ] Rerun package tests and simulator integration with a deterministic local fixture server, then one explicit manual public check. Live network failure does not invalidate parser tests and is not reclassified to make UI green.
- [ ] Commit: `feat(ios): add bounded native Telegram probes`.

## Task 3: Foreground lifecycle and durable observation history (2–3 days with Task4 UI)

**Files:** Create `HistoryEntry.swift`, `HistoryStore.swift`, `HistoryCoverage.swift`, `HistoryStoreTests.swift`, `HistoryCoverageTests.swift`; extend `MonitorController.swift`; test `ios/TgWatchIOSTests/MonitorLifecycleTests.swift`.

**Interfaces:**
- `HistoryEntry: Codable` stores `schemaVersion=1`, epoch, observation time and ProbeStatus; no authentication material. `HistoryStore` actor exposes `load() async throws -> [HistoryEntry]`, `append(_ entry: HistoryEntry) async throws`, `clear() async throws`.
- `ObservationInterval` contains `from: Date`, `to: Date`, `status: ProbeStatus`. `HistoryCoverage.intervals(entries: [HistoryEntry], now: Date) -> [ObservationInterval]`: no backward epoch bridging; gaps >120s UNKNOWN, last observation no longer fresh after120s.
- `@MainActor MonitorController` has `checkNow()`, `cancel()`, `setMonitoring(_ enabled: Bool)`, `setActive(_ active: Bool)`; consumes ProbeRunner/HistoryStore and injected clock. Single owner of task+epoch; saved enabled preference does not replay stale results.

- [ ] Write tests for overlap/tick skipping, background cancellation, resume fresh batch, stale at121s, clock rollback, network-generation race, delayed disk write, corrupt file, 10,001 rows and >7-day retention. Assert no fabricated history entries during suspension.
- [ ] Run package tests filtered to `HistoryStoreTests|HistoryCoverageTests`; run `MonitorLifecycleTests` in Simulator and record RED.
- [ ] Implement atomic local file replacement, bounded decode/file size (8MiB ceiling), queued dirty entries retained on write failure, distinct “history unavailable” UI error. Preserve unreadable file for diagnostics inside app storage; do not silently replace with successful empty history. Retention applied after successful append; no Android import claims.
- [ ] Implement foreground60s loop with injected clock; every inactive/background change cancels batch and closes transport. Returning active cannot display stored OK as live. History status/freshness shown separately.
- [ ] Verify GREEN with the same tests, process relaunch, and simulator lifecycle transitions. Commit: `feat(ios): add foreground lifecycle and bounded history`.

## Task 4: Usable simulator MVP and reproducible test command (included in 2–3 days above)

**Files:** Complete `MonitorView.swift`, `HistoryView.swift`, localizations, privacy manifest; create `MonitorFlowTests.swift`, `HistoryFlowTests.swift`, `scripts/test-ios.sh`, `docs/ios/README.md`.

- [ ] XCUITests first: tap check/cancel, one batch; RU/EN status and timestamp, unknown/stale explanation, fixture failure and denied/offline cases, history delete confirmation, VoiceOver identifiers and largest Dynamic Type with reachable controls. No tests depend on the current internet.
- [ ] Run simulator tests and record expected initial failures. Add a launch-argument fixture mode compiled only for DEBUG; release build cannot choose fake healthy data.
- [ ] Implement UI and resource copy, “checking while open” explanation, no notification permission prompt since MVP does not deliver notifications. Record privacy-manifest required-reason declarations only for APIs actually used, cross-check latest Apple guidance during implementation.
- [ ] Add script that checks Xcode/project/scheme/runtime and fails clearly if unavailable, accepts explicit simulator UDID, runs build/test without signing/provisioning, emits xcresult; never downloads runtimes, accepts licenses, signs in or runs `-allowProvisioningUpdates` automatically.
- [ ] Run full tests and smoke launch using script on Mac; capture screenshot and exact runtime/build SHA, result counts and skips. No physical-device claim from simulator. Commit: `feat(ios): complete foreground simulator MVP`.

Standard verification on a Mac after the project exists (`SIMULATOR_UDID` comes from inventory; choose a fresh result directory):

```bash
swift test --package-path ios/TgWatchCore
xcodebuild build -project ios/TgWatchIOS.xcodeproj -scheme TgWatchIOS \
  -destination "id=$SIMULATOR_UDID" CODE_SIGNING_ALLOWED=NO
xcodebuild test -project ios/TgWatchIOS.xcodeproj -scheme TgWatchIOS \
  -destination "id=$SIMULATOR_UDID" CODE_SIGNING_ALLOWED=NO \
  -resultBundlePath /tmp/tgwatch-ios-tests.xcresult
```

Initially run locally. A future separate `ios.yml` may run a single pinned macOS/Xcode simulator job with paths limited to `ios/**` and `scripts/test-ios.sh`, read-only contents permission and no signing secrets. Workflow creation is a separate proposed change because the current connector has a known workflow-edit scope restriction; do not widen it or modify Android/Mac workflows to evade it. CI lack is recorded as pending, not papered over by unrelated green Android checks. No workflow dispatch for this documentation proposal.

## Task 5: First device installation and evidence (1–2 days plus access wait)

**Files:** `docs/ios/device-testing.md`, untracked local signing configuration (never credentials in repo).

- [ ] Before any account operation, obtain exact account/team choice from user and confirmation of the proposed Xcode signing action. Prefer existing assets. Simulator gates must already pass. Record proposed bundle identifier `ru.tgwatch.ios` only after availability/team check; do not register it in this planning stage.
- [ ] User signs into Xcode and authorizes Personal Team/development provisioning on the chosen Mac; device trust/Developer Mode performed on the physical phone. Stop for auth/enrollment/2FA decisions. No purchase or Developer ID creation for this step.
- [ ] Build/install/run through Xcode with the selected team/device; do not include private UDID/certificates in public artifacts. This is Apple Development + development profile, not TestFlight distribution.
- [ ] Device acceptance: Wi-Fi vs cellular, airplane mode/captive network, VPN path transition, cancel/unplug, lock/unlock, force-quit/manual restart, Low Power Mode, reinstall/update without unintended history reset. Real Telegram result remains evidence-labelled; do not fabricate success when the network is inaccessible.
- [ ] Record model/iOS, exact source SHA, tested scenarios, failures and evidence location. Public doc redacts identities. Document Personal Team profile expiration/reinstallation; TestFlight is a separate branch of work only if requested and membership confirmed.
- [ ] Commit only instructions/evidence summary: `docs(ios): record verified device test procedure`.

## Later scopes, not implementation prerequisites

Regional sites/explicit regional restriction proof; custom HTTPS; extended incident center; opportunistic BGAppRefresh with gaps; widgets; device backup/export; TestFlight and production distribution each get a separate scope/test decision. If timely remote alerts are needed, propose an always-on server/Mac plus visible APNs only after approving data flows, ownership and Developer Program; those probes measure that host, not iPhone connectivity. Never use silent pushes as a periodic polling guarantee.

## Self-review and handoff

Spec requirements map to Tasks1–5; Review Focus maps to Tasks1/2 (network), Task3 (time/storage/lifecycle), Task4/5 (actual UI/device). Caps, status values and interfaces match the spec; implementation remains unchecked. Estimates sum to6–11 engineering days excluding access/review delays. No iPhone executable, simulator run, certificate issuance or Apple membership is claimed by this plan.

Next concrete decision: confirm the foreground Telegram MVP, test device/iOS and Mac; then execute Task1 under the chosen implementation workflow. The agent can implement natively in the same task after design approval; extra parallel agents are optional, not a prerequisite to buying tools or changing accounts.
