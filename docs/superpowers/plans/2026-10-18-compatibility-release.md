# Compatibility and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Verify the supported Android floor/new Android runtime and package the completed features with truthful evidence.
**Architecture:** Reuse existing emulator smoke, signed upgrade, dual-app audit and reproducible release pipeline. Qualify target36 separately from widening the runtime test matrix.
**Tech Stack:** Existing pinned Gradle8.14.6/AGP8.13.2/Kotlin2.4.21/JDK17, SDK, Python release scripts.
**Spec:** `docs/superpowers/specs/2026-10-18-app-completion-design.md`, sections A and final verification.

## Global Constraints

- Min phoneAPI26; phone native matrix26/29/35/36, Wear min30 and existing native smoke.
- No changing published tags/assets, production signing certificate, protected user branch or historical evidence.
- One Gradle invocation at a time in the shared cloud workspace; no new worktree needed.
- Do not claim real-device power validation, F-Droid acceptance or unexecuted tests passed.
- Use unsigned local builds; production key stays in GitHub Secrets.

## Review Focus

- Supported minAPI26 actually installs and exercises the existing app, rather than compiling only.
- Android36 background restrictions are tested with user-controlled starts, alarms and locked boot.
- Fresh test result directories cannot accidentally reuse reports from another emulator/API.
- Upgrade keeps per-goal settings/history and excludes new private condition data from exports.
- Signed/unsigned core artifacts remain identical under normal/restrictive umasks.

### Task 1: Current-target runtime matrix and documentation corrections

**Files:** `.github/workflows/build-apk.yml`, `README.md`, `docs/README.en.md`, `CONTRIBUTING.md`, `docs/development-roadmap.md`; existing scripts/native tests only if actual runtime failures demonstrate a required compatibility fix.
**Interfaces:** Existing scripts unchanged unless a named failure is reproduced; CI matrix `api: [26,29,35,36]`, same x86_64 default images and root-capable disposable emulator contract.

- [x] Correct private backup URL, v3/v2, p50/p95, webhook fields and version documentation against current implementation. Preserve historical release evidence.
- [x] Install official images26/36 with verified SDK metadata; create owned disposable AVDs and run current-target35 phone tests/smoke on both. Record exact executed cases, failures/skips and smoke stages. Keep26/36 reports separately. No tests are skipped merely to make the new matrix green.
- [x] If an actual runtime bug appears, add a regression and observe RED before a minimal fix; rerun that regression and suite. Emulator setup issues are diagnosed independently and do not justify product deadline/TLS changes.
- [x] Run Python suite and `git diff --check`; commit `ci: verify minimum Android and Android16 runtimes` with documentation fixes separately where useful.

### Task 2: Qualify phone target36

**Files:** `app/build.gradle.kts`, Android build/setup documentation, relevant native tests; exact behavior changes determined from official Android16 target-change documentation.
**Interfaces:** Phone compileSdk/targetSdk36 and pinned compatible SDK/build tools after qualification; Wear stays target35. No incidental toolchain upgrade.

- [x] Record applicable official target36 behavior changes in `docs/android16-compatibility.md`: edge-to-edge/insets, large screens/resizing, foreground/background execution, exact alarms, notifications and file grants. Distinguish changes that affect this app from irrelevant API notes.
- [x] Add failing native assertions for applicable behavior gaps under target36; assertions check actual visible controls/insets and lifecycle behavior, not source strings. Run RED against changed target before implementing fixes.
- [x] Upgrade only phone SDK values and required SDK pin; implement proven compatibility fixes, run phone matrix26/29/35/36 plus existing Wear checks. Do not bump target36 if required checks remain unresolved; record actual blocker.
- [x] Commit `build: qualify phone Android16 target behavior` when checks pass.

### Task 3: Integrated verification, review and publishable package

**Files:** `version.properties`, changelog/new release note, dependency evidence/metadata, distribution docs, environment reusable instructions when changed.
**Interfaces:** New version1.20.0/code1020000 if all planned behavior ships together; if scope changes, choose SemVer based on actual shipped behavior without rewriting1.19.1. Release still contains twelve phone/Wear assets with the current signer continuity.

- [x] After all four feature plans: run core/phone/Wear JVM and bounded heap, full Python suite, detekt/lint, both debug/release APKs, all mandatory native and signed-upgrade paths, actual core mask reproducibility and runtime/test audit gates. Collect counts from fresh reports, distinguish local/CI results.
- [x] Request one independent whole-branch review using all plans' Review Focus. Address confirmed important failures with reproducing tests; preserve privacy, physical slots and schema compatibility. Report any unresolved item by name.
- [x] Prepare bilingual release notes and phone/Wear APKs. Verify source revision/version/signature, exact twelve assets, SBOM/inventory and both audits, then use the existing release workflow under the user's release authorization. Existing release tags remain immutable. A blocked external publication is reported, not represented as completed.
- [x] Update F-Droid candidate to the actual verified tag, run normal readmeta/lint/build and unsigned/payload comparison; keep catalog submission separate. Save tested API26/36 environment setup/start instructions through the configuration draft without replacing network/secrets.
- [x] Commit evidence and final docs. Preserve the user's working branch and leave a clean, reviewable development branch/main according to existing integration authorization.

## Runtime qualification evidence —19October2026

Whole-branch review completed against baseeb22af6 and candidatedf68285; the confirmed
queued-v2-heartbeat defect was reproduced and fixed, including preservation of legacy
event retry semantics. The minor power explanation issue was fixed. No deferred
review finding remains. Later native fixture fixes preserve production behavior and
physical slot ownership; their RED/GREEN evidence is retained in the SDD ledger.

Current integrated run39236704240, sourcee2d4992, passed all seven required build/device
jobs. The SDK bump introduced no demonstrated production compatibility gap: the new
real-window assertion passes alongside existing layout/lifecycle cases, rather than
inventing a production failure to satisfy a RED checkbox. Official source applicability
is documented in docs/android16-compatibility.md. Publication, signed upgrades and
exact-tag F-Droid/payload verification remain Task3 gates until actually completed.

## Signed-predecessor fixture follow-up

Tagv1.20.0/source d38ebb0 remains immutable and unpublished. Run39237727506 passed
all seven quality/native jobs, signed both APKs, verified signer continuity and
completed the real signed1.10.47 upgrade. Its latest-stable path installed1.19.1
fresh, which deliberately opened OnboardingActivity with monitoring off. The
fixture's automatic-first-observation assumption was invalid. Prepare that old
installation through its real guide/start controls, then retain the original
genuine-observation/data-preservation gates. New patch version1.20.1/code1020001
uses a new immutable tag; no previous tag or release asset is changed.

## Final delivered result

Release[v1.20.1](https://github.com/qlolp/TgWatch/releases/tag/v1.20.1), source
4b2aba4d1c87606ed482752cf027c85e5896dece, was published after run39241608647
passed all eight jobs, including both real signed predecessor upgrades. All twelve
public assets were downloaded and independently checked for identity/hash/signer,
complete OSV/runtime Trivy evidence and phone/Wear SDK separation. Normal exact-tag
F-Droid readmeta/lint/build and independent clean assembly matched byte-for-byte;
apksigcopier compared both unsigned APKs to production successfully.

Tested SDK36/Python install and startup instructions were saved through the cloud
configuration draft; saving does not publish/apply the environment. Full artifact
evidence is in docs/distribution/verification-1.20.1.json, and all20rulings/review
findings are retained in completion-1.20.1.md. Historical1.19.1 evidence and the
unpublished1.20.0 tag remain unchanged. No external catalog submission or physical
OEM battery validation is claimed. GitHub metadata writes remain blocked by403.
