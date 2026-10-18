# Custom HTTPS Health Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user require an HTTP code and a top-level JSON string value for their own HTTPS target.
**Architecture:** The portable probe core validates a bounded condition. Phone adapters capture one private configuration; the current durable endpoint transaction fences condition changes together with URL changes.
**Tech Stack:** Kotlin/JVM17, existing StrictJsonReader and HTTP transport, Android CP storage.
**Spec:** `docs/superpowers/specs/2026-10-18-app-completion-design.md`, section C.

## Global Constraints

- CUSTOM only; default 2xx/HEAD behavior unchanged, Matrix/WhatsApp/Telegram unchanged.
- Expected code200..599 or null; JSON field1..64chars and expected value0..256chars supplied together.
- Body cap32768bytes, strict UTF-8, maximum depth10, no duplicate keys, identity encoding.
- One GET for body mode; no redirects/TLS bypass/new network slots/deadlines.
- URL, field and value are CP credentials, excluded from every portable/public export.
- Existing v1/v2/v3 backups and pending private transactions remain readable.

## Review Focus

- HTTP200 error body must fail when the required field differs.
- Nested duplicate keys, malformed UTF-8 and cap-boundary unknown-length streams fail closed.
- A condition-only edit invalidates an old in-flight response just like a URL edit.
- Crash between private staging and public revision publication replays the whole configuration.
- Expected secret values never appear in exception text, diagnostics, sharing or backups.

### Task 1: Portable condition and bounded verification

**Files:** Create `probe-core/src/main/kotlin/ru/tgwatch/probe/HttpProbeCondition.kt`; modify `ProbeTypes.kt`, `HttpEndpointProbe.kt`, `NetworkProbe.kt`; test `probe-core/src/test/kotlin/ru/tgwatch/probe/HttpProbeConditionTest.kt` and existing HTTP tests.
**Interfaces:** `HttpProbeCondition(expectedCode: Int?=null, jsonField: String?=null, expectedValue: String?=null)` validates paired values. `ProbeEndpoint` gains trailing `condition: HttpProbeCondition?=null`. Condition has a redacted `toString()`. `HttpProbeResult` gains trailing `failure: HttpConditionFailure?=null`; enum values `CODE_MISMATCH`, `JSON_NOT_CONFIRMED`. Add matching `HTTP_CODE_MISMATCH`/`HTTP_JSON_NOT_CONFIRMED` ProbeDetailCode values and map them in core NetworkProbe and phone adapter. Bounded parser failures map to this public enum, never raw body.

- [ ] Write assertions: expectedCode204 rejects200; fieldstatus/ok rejects `{"status":"error"}`, accepts `{"status":"ok"}`, rejects missing/nonstring/duplicate, accepts empty expected string. Body mode uses GET once; default uses HEAD and fallback only405/501. Add nested duplicate/depth11, truncated, malformed UTF-8, identity/compressed and32768/32769byte cases.
- [ ] Run `bash ./gradlew :probe-core:test --tests '*HttpProbeConditionTest'`, expected RED because condition is absent.
- [ ] Implement validation with existing streaming StrictJsonReader; consume the whole object and finish input. Reuse one bounded UTF-8 body reader with explicit cap32768 for CUSTOM and unchanged65536 for Matrix. Reject unknown-length bodies reaching the cap; accept exact known length only when complete. Register cancellation before blocking IO, retain3s per-connection timeout and enclosing deadline. Require expected code or default2xx before confirming content.
- [ ] Run all `:probe-core:test` and phone JVM tests. Test stalled cancellation using actual blocking stream seam, then assert physical lease remains owned until delegate finally. No unbounded body buffering.
- [ ] Commit `feat: add bounded custom HTTPS response conditions`.

### Task 2: Atomic private configuration and Android UI

**Files:** Create `app/src/main/java/ru/tgwatch/PrivateProbeConfig.kt`; modify `TargetPreferences.kt`, `TargetSettingWrites.kt`, `TargetConfigTransactions.kt`, `TargetSettingsDialog.kt`, `TargetEndpoints.kt`, `TargetRunner.kt`, `TargetMonitoring.kt`, `PhoneProbeAdapter.kt`, RU/EN strings; test `PrivateProbeConfigTest.kt`, `TargetRunnerTest.kt` and Android `CustomHealthIntegrationTest.kt`.
**Interfaces:** `PrivateProbeConfig(url: String, condition: HttpProbeCondition?=null)`; `PrivateProbeConfigCodec.encode(value): String` and `decode(raw: String): PrivateProbeConfig` use a versioned CP-only envelope and accept legacy raw HTTPS URL. Existing private transaction string slot transports that opaque envelope; no condition enters its DP marker or public backup payload. Extend `TargetSettingWrites.endpoint` and `TargetConfigTransactions.change` with trailing condition defaultnull; clear/default actions clear the condition too. `TargetRunner.check` gains trailing condition defaultnull and TargetMonitoring captures it with the same fenced configuration as the URL.

- [ ] Write private codec roundtrip/legacy tests and native condition-only edit/replay tests. Assert actual private field/value are absent from public pending marker, backups v2/v3, diagnostics, webhook, CSV, share and safe failure text. Prove a late old response is rejected after condition edit and stop during private IO still wins.
- [ ] Run named JVM/native tests and observe RED at missing structured configuration support.
- [ ] Implement full CP configuration as one serialized value and one durable endpointRevision transaction. Keep old pending transactions readable; target remains parked until replay completes. UI adds optional code, field and value controls only for CUSTOM; no autofill/saved-state, FLAG_SECURE and clear-on-dismiss. Safe diagnostic resources describe only mismatch category/code.
- [ ] Run complete core/phone JVM, lint, native custom-health tests and existing target preference/restore tests. Explicitly test old Matrix private URL, custom default behavior and secret redaction.
- [ ] Commit `feat: configure private custom health conditions atomically`.

### Task 3: User documentation

**Files:** Modify `docs/multiservice.md`, `docs/diagnostics.md`, RU/EN overview.

- [ ] Describe default2xx, optional code, `status=ok` example, body limits and excluded credentials. State that a reachability/health result does not prove delivery of application messages.
- [ ] Verify documented examples with the same parser tests; run `git diff --check`.
- [ ] Commit `docs: explain optional HTTPS content checks`.
