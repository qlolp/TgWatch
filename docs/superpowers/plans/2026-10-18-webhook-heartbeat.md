# Webhook Heartbeat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Provide opt-in Home Assistant freshness signals without converting cached OK into a live measurement.
**Architecture:** A separate JSON_V2 format adds heartbeats to the current coordinator. All candidates share its single physical transport, cancellation and generation boundaries; published measurements drive delivery without new timers.
**Tech Stack:** Kotlin/JUnit4, org.json, existing HTTPS webhook, Home Assistant YAML/Python fixture tests.
**Spec:** `docs/superpowers/specs/2026-10-18-app-completion-design.md`, section D.

## Global Constraints

- TEXT and JSON v1 retain existing behavior and exactly eight JSON v1 fields.
- JSON_V2 is explicit opt-in; no additional alarm, probe, service or background schedule.
- One physical request, deadline10000ms; same event filters, baseline and generation fences.
- Heartbeat per goal at most once300000ms, monotonic; four fixed IDs, no unbounded queue.
- Credentials remain CP-only and excluded from public/portable outputs.
- If power plan already ran, runtime expected interval includes600s without crash.

## Review Focus

- An initial measured OK sends only in the new format, without creating a recovery event.
- A timed-out transport retains the physical slot even after changing format/address.
- Heartbeat success must not suppress a pending outage/recovery or change its previous_status.
- A dead process or no new measurements expires in HA; replayed old OK cannot refresh TTL.
- Test/malformed v2 packets and device clock skew cannot mark a target healthy.

### Task 1: Pure heartbeat policy and explicit payload version

**Files:** Create `app/src/main/java/ru/tgwatch/WebhookHeartbeatPolicy.kt`; modify `WebhookEvent.kt`, `WebhookPayload.kt`; tests `WebhookHeartbeatPolicyTest.kt`, `WebhookEventTest.kt`.
**Interfaces:** Add `WebhookFormat.JSON_V2`, `WebhookEventKind.HEARTBEAT("heartbeat")`; `DELIVERY_EVENTS` explicitly excludes TEST and HEARTBEAT. `WebhookHeartbeatPolicy.due(observation: WebhookObservation,nowElapsed: Long): Boolean`, `started(serviceId: String,nowElapsed: Long)`, `forget(serviceId: String)`, `reset()`. Due requires measured/fresh/positive timestamp/known status/fixed service; only admitted starts consume300000ms. `WebhookPayload.encode(event,format,sentAtMs: Long?=null)` requires positive sentAtMs for v2, ignored for legacy formats.

- [x] Write tests asserting due at first freshOK and exact300000ms, false at299999ms, false for UNKNOWN/stale/unmeasured, independent four services and safe elapsed rollback. Assert v1 exact key set unchanged. V2 exact eleven keys = eight legacy keys plus sent_at_ms/monitoring/is_stale; schema_version2, heartbeat previous_status null, checked_at_ms equals source timestamp rather than sending time. Explicit v2 test has null measurement fields, monitoringfalse/is_staletrue.
- [x] Run `bash ./gradlew :app:testDebugUnitTest --tests '*WebhookHeartbeatPolicyTest' --tests '*WebhookEventTest'`, expected RED for absent format/policy.
- [x] Implement bounded policy and payload. Real accepted observations set monitoringtrue/is_stalefalse. Only explicit test uses false/true without claiming observation. Heartbeat has no effect on WebhookEventTracker baseline. Keep v1 test and serialized field semantics intact.
- [x] Run complete phone JVM suite; verify all existing TEXT/JSON tests still pass. Add five unknown IDs and show policy retains only the four supported IDs.
- [x] Commit `feat: define opt-in webhook heartbeat schema`.

### Task 2: Coordinator, credential settings and UI integration

**Files:** Modify `WebhookNotifier.kt`, `WebhookPolicy.kt`, `WebhookSettings.kt`, `WebhookDeliverySettingsUi.kt`, RU/EN resources; tests `WebhookCoordinatorTest.kt`, `WebhookBlockedHealthTest.kt`, `MultiTargetWebhookTest.kt`; native `WebhookSettingsIntegrationTest.kt`.
**Interfaces:** Existing `onPublished(WebhookObservation)` is the sole automatic source. Coordinator keeps a last candidate per service, admits pending events before due heartbeat, and calls policy.started only after actual transport admission. `WebhookSettings.configuration` recognizes JSON_V2, default remains TEXT. Switching format changes the same credential generation and cancels stale logical decisions.

- [x] Write tests: four repeatedOK goals produce at most one physical request; event wins over waiting heartbeat; busy/rejected submit does not consume heartbeat interval; admitted HTTP failure does consume it; next eligible observation is the only retry source. A frozen expired candidate is dropped. Stale callback after URL/format switch cannot update new delivery health. Same-service heartbeat and recovery preserve tracker baseline and filter behavior.
- [x] Run named coordinator tests, expected RED for missing heartbeat path rather than unrelated setup.
- [x] Integrate policy with current synchronized coordinator. Preserve at most four heartbeat candidates, no transport retry timer, separate heartbeat throttle from event rate limit. Reset/forget clears appropriate candidates and policy state; active physical transport stays owned until finally. Add resource-based v2 selector/explanation and test message support without enabling delivery.
- [x] Run all JVM, lint, native settings tests; explicitly execute stalled-request/format-switch and multi-target regressions. Check no backup schema or manifest service/alarm was added.
- [x] Commit `feat: deliver fresh heartbeat through existing webhook lane`.

### Task 3: Home Assistant receive-time expiry

**Files:** Create `docs/examples/home-assistant/tgwatch-heartbeat.yaml`, `scripts/tests/test_ha_heartbeat.py`; modify `docs/automation.md` and RU/EN overview. Existing v1 example remains unchanged.
**Interfaces:** V2 example uses authenticated user-owned HTTPS webhook ingress, only four fixed service IDs, allowlisted status/event/schema, and receipt-time TTL720seconds. No MQTT broker or new app destination.

- [x] Write executable fixture assertions: schema1/test/UNKNOWN/malformed rejected; valid heartbeat stores measurement timestamp plus local receipt time; unavailable after720seconds, fresh exactly before boundary; an old/delayed packet cannot replace a newer measurement or extend its TTL; phone clock offset alone does not keep a sensor live. A duplicate checked_at_ms heartbeat does not refresh TTL. Out-of-order measurement handling uses source ordering only within the accepted stream; clock rollback requires unavailable/reset rather than accepting arbitrary replay.
- [x] Run `python3 -m unittest discover -s scripts/tests -p test_ha_heartbeat.py -v`; expected RED against missing YAML behavior, not import errors.
- [x] Implement YAML using persisted helper state and HA local receipt time; expose original checked_at_ms as descriptive data. Document restart/clock-change reset and best-effort delivery. Preserve original event example and clarify v1 never supplies a continuous online state.
- [x] Run all Python tests, verify actual YAML parsing/templates through the established example-test pattern, then `git diff --check`.
- [x] Commit `docs: add freshness-aware Home Assistant heartbeat example`.
