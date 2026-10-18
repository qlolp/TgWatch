# Target Power Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce optional targets' background frequency without changing existing defaults or misreporting freshness.
**Architecture:** A pure interval policy feeds the existing TargetSchedule. Android supplies power inputs; one nearest wake-up and current physical admission remain authoritative.
**Tech Stack:** Kotlin, native Android, JUnit4, existing instrumentation.
**Spec:** `docs/superpowers/specs/2026-10-18-app-completion-design.md`, section B.

## Global Constraints

- Phone minAPI26; target35 until the compatibility plan qualifies target36.
- Telegram's profile, 16 primary/3 secondary physical slots, one foreground service remain.
- New opt-in defaults false; never enables a goal. No periodic battery polling.
- User interval remains 60–300s for secondary goals; runtime interval can reach 600s.
- One manual follow-up; monotonic scheduling; backup v3 schema stays unchanged.
- New user strings go in RU/EN resources. Protected branch is untouched.

## Review Focus

- A runtime600s snapshot survives persistence and webhook conversion without a constructor failure.
- Screen changes during a running check do not admit a second physical request.
- Charging removes low-charge restriction, but not system power-save restriction.
- Restoring an old/new backup resets this device option to false without enabling targets.
- A widened future interval never retroactively fills unmeasured history.

### Task 1: Pure policy and interval provenance

**Files:** Create `app/src/main/java/ru/tgwatch/TargetPowerPolicy.kt`; modify `TargetSchedule.kt`, `TargetRunner.kt`, `TargetRepository.kt`, `TargetSnapshot.kt`, `TargetStateStore.kt`, `WebhookEvent.kt`; tests `TargetPowerPolicyTest.kt`, `TargetScheduleTest.kt`, `TargetWebhookObservationTest.kt` under existing test roots.
**Interfaces:** Produces `TargetPowerState(screenOn: Boolean, powerSave: Boolean, batteryPercent: Int?, charging: Boolean)`; `TargetPowerPolicy.intervalSec(configured: Int, enabled: Boolean, state: TargetPowerState): Int`; `TargetPowerPolicy.MAX_EFFECTIVE_SEC=600`. Extend `TargetAttempt` with trailing nullable `expectedIntervalSec: Int?=null`; repository uses it or existing configured interval. `TargetSchedule.setInterval(id: TargetId, intervalSec: Int, nowElapsed: Long)` recomputes waiting deadline from last completion, never changes enabled/in-flight state.

- [ ] Write tests with exact assertions: `intervalSec(60,false,off)==60`; enabled screen-off=300; charge15/unplugged=600; charge16/screen-on=60; charging+powerSave=600; charging+lowCharge/screen-on=60; null battery/screen-on=60. Invalid percent is treated as unknown at Android boundary.
- [ ] Run `bash ./gradlew :app:testDebugUnitTest --tests '*TargetPowerPolicyTest'`; expected RED because policy is absent.
- [ ] Implement policy and schedule override. Runtime domain10..600 is separate from user configuration bounds. `TargetSnapshot`, `WebhookObservation` validation and `TargetStateStore.load` use runtime max600. No backup settings ranges are widened. Existing attempt callers keep their default.
- [ ] Add/execute regressions proving a 600s published snapshot persists, converts to webhook and stays fresh at600s; old observation endpoints stay fixed. A running target gets one manual follow-up across interval changes; removing the restriction recalculates from completion without overlap. Run `:app:testDebugUnitTest`, expected all executed tests pass.
- [ ] Commit `feat: add bounded secondary-target power policy`.

### Task 2: Android power inputs, opt-in and truthful UI

**Files:** Create `TargetPowerSettings.kt`, `TargetPowerMonitor.kt` beside TargetMonitoring; modify `TargetMonitoring.kt`, `MonitorService.kt`, `TargetSettingsDialog.kt`, `BackupManager.kt`, `TargetRestore.kt`, RU/EN strings; create `app/src/androidTest/java/ru/tgwatch/TargetPowerIntegrationTest.kt`.
**Interfaces:** `TargetPowerSettings.enabled(ctx: Context): Boolean`; `setEnabled(ctx: Context,value: Boolean): Boolean`; DP public pref `secondary_power_saving`, defaultfalse, omitted from exports and explicitly reset on restore. `TargetPowerMonitor(ctx: Context, changed: (TargetPowerState)->Unit): Closeable` owns a dynamically registered receiver and initial sticky battery read. Its callback posts onto the existing monitor worker. It does not retain Activity.

- [ ] Write instrumentation assertions for defaultfalse, option toggle, 15/16% and charge/system-save events, runtime interval persistence and restore reset. Verify listener close/unregister and no extra AlarmManager source. Use injected input seam for deterministic battery state rather than modifying a developer phone.
- [ ] Run `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.TargetPowerIntegrationTest`, expected RED for absent adapter/settings.
- [ ] Implement sticky ACTION_BATTERY_CHANGED scale validation plus screen/power-save callbacks. Feed policy interval into schedule and the admitted TargetAttempt. At completion publish a copy of that attempt with expectedIntervalSec recomputed from the latest power state, then finish the schedule with the same interval. This updates the new observation's future expectation, never an old row's endpoint. Display configured/effective interval and reason. Use resources and existing settings worker for disk writes.
- [ ] Run JVM suite, lint and the named device test. Assertions include enabling one option does not enable another target, no duplicate wake-up, no retroactive coverage, and restoring resets only this option rather than existing alert preferences. Native test must execute rather than skip.
- [ ] Commit `feat: expose opt-in secondary-target battery saving`.

### Task 3: Battery measurement procedure

**Files:** Create `docs/power-validation.md`; modify `docs/multiservice.md`.
**Interfaces:** Documentation only; do not infer real-device energy savings from emulated power events.

- [ ] Document matched baseline/opt-in runs on the same physical device: same radio/targets/profile, charging excluded, screen-off12h, record start/end charge, `dumpsys batterystats`, elapsed checks/coverage and alarms. Include VPN and manufacturer background settings in local notes, without automatically exporting addresses.
- [ ] Describe the600s maximum and resulting detection delay. Record which measurements actually ran; publish no unmeasured percentage improvement.
- [ ] Commit `docs: describe target power behavior and measurement`.
