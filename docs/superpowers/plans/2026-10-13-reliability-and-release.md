# TgWatch 1.16 Implementation Plan

> **For agentic workers:** Use independent subsystem owners and review the complete
> branch before integration. The user explicitly requested autonomous execution
> and publication; no further design approval is needed.

**Goal:** Preserve trustworthy monitoring results and private settings, then publish
a verified update using the existing production signing identity.

**Architecture:** Keep native Android adapters and bounded workers. Small policies
coordinate admission and cancellation; immutable cached snapshots keep history
work away from the main thread. The existing release policy owns publication.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2, JDK17, Android26–35,
Python3 and existing GitHub Actions.

**Spec:** [Design](../specs/2026-10-13-reliability-and-release-design.md).

## Global constraints

- Never change or delete `claude/project-thread-s0ejzl`.
- Preserve seven-day/65,000-row limits, Direct Boot and all supported backup readers.
- Preserve one physical webhook request, even when cancellation is ignored.
- Keep production certificate continuity and immutable releases; debug is test-only.
- Preserve meaningful assertions and the existing detekt baseline.

## Review focus

- A transport that ignores interruption must keep its physical slot and expose it.
- Cancellation and network changes at the publication boundary must discard stale data.
- Failed throttle persistence must not enqueue work or consume the stopped-call budget.
- Slow history loads must not block foreground startup or broadcast handling.
- Mutated source, checksum, signer, manifest or published bytes must fail closed.

## Tasks

### 1. Monitor publication and statistics

- [x] Reproduce interruption, failed last-success writes and blocked cache reads.
- [x] Fix `NetworkProbe`, `MonitorService`, `LastSuccessStore`, `DailyStatsCache`.
- [x] Move `DailySummaryReceiver` and its manual preview onto a bounded dispatcher.
- [x] Check revision under publication lock and preserve cancellation retry semantics.
- [ ] Verify focused JVM regressions and Android lifecycle scenarios.

### 2. Automation and webhook settings

- [x] Reproduce a timed-out/cancelled physical request hidden after reset.
- [x] Expose blocked state without releasing the physical slot.
- [x] Mask the token and keep explicit copying; distinguish invalid/storage failure.
- [x] Atomically coordinate CHECK_NOW receipt, storage and worker admission.
- [ ] Verify regressions including stopped calls and commit failure.

### 3. Publication and dependencies

- [x] Preserve the reviewed audit and dependency heads as merge parents.
- [x] Regenerate and install the Python lock with artifact hashes.
- [x] Reproduce bad APK/checksum/certificate acceptance and source mutations.
- [x] Enforce APK/source checks and verify all six downloaded assets.
- [x] Disable persisted checkout credentials; pin the legacy Gradle checksum.
- [x] Verify tooling regressions and scan resolved Maven/runtime inventories.

### 4. Release and final verification

- [x] Protect sensitive dialogs, reduce age-only tile polling, update RU/EN copy.
- [x] Update version, notes and disabled F-Droid candidate to 1.16.0/1016000.
- [x] Run full local checks and obtain independent whole-branch review.
- [ ] Pass exact PR Android29/35 CI; merge and publish a fresh immutable tag.
- [ ] Verify production signature, checksum, manifest and all six release assets.
- [ ] Check branch/stash preservation and save any necessary environment refresh.
