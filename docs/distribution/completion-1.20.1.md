# TgWatch1.20.1 completion report

The user approved the completion specification/plans and requested execution on
19October2026. All four feature plans are implemented. Exact published identities,
asset hashes, signing/audit results and the publication workflow are recorded in
[verification-1.20.1.json](verification-1.20.1.json). Historical verification remains
in its own versioned files.

## Delivered behavior

| Area | Result |
|---|---|
| Secondary power | Device-only opt-in; screen/power/battery inputs; effective300/600s intervals and matching freshness/watchdog state; no new periodic alarm |
| Custom HTTPS | Expected status and bounded strict JSON condition; atomic credential envelope and late-response fence; legacy URL support; private configuration excluded from portable/public exports |
| Webhook | Explicit JSONv2 with exact eleven fields and five-minute heartbeat admission from existing publications; one physical slot; legacy TEXT/JSONv1 preserved; executable HA TTL/order fixtures |
| Resources/sharing | RU/EN resolved from caller resources; immutable typed presentation; one frozen capture locale/document for preview/text/PNG; recreation clears only old queued work |
| Compatibility | Phone min26/target36, independent Wear min30/target35; full native26/29/35/36 and Wear qualification |

## Review and regressions

One independent whole-branch review compared baseeb22af6 to candidatedf68285,
including the corrected platform-inset test placement. It found no Critical issue.
The Important queued-heartbeat finding was reproduced: a newer UNKNOWN could leave
an earlier OK waiting for the transport. The fix evicts unsent v2 candidates,
preserves measured transition baselines and retains legacy retry behavior. Two new
blocked-lane tests failed before the fix and pass after it. An unchanged legacy
retry regression caught an over-broad first attempt; the delivered fix is v2-only.

The Minor power-dialog explanation issue was fixed by resampling on user updates.
No polling/alarm was added. No independent-review finding was deferred.

The queued-share recreation bug was separately reproduced with a real worker
barrier. Only the obsolete Activity's queued task is discarded; active work retains
its physical ownership. Native tests exercise pending locale changes, actual Canvas
labels, PNG decoding, large fonts, TalkBack and URI/privacy boundaries.

Native qualification also corrected two fixture assumptions: the permission renderer
moved out of MainActivity, and ordered GET response delivery can precede physical
compute teardown. The latter now uses code4/null backoff within the original absolute
12s deadline. A deterministic finish-barrier unit test proves that successful reply
still owns its physical slot and cannot lend another goal's cache. CHECK budgets,
security assertions and production deadlines are unchanged.

## Qualification evidence

Run[39240499086](https://github.com/qlolp/TgWatch/actions/runs/39240499086),
source4b2aba4d1c87606ed482752cf027c85e5896dece, passed all seven required build/device
jobs for1.20.1. The tag publication workflow repeats mandatory checks at that same
immutable source revision; its final results are in the versioned JSON record.

| Check | Observed result |
|---|---|
| Phone JVM in integrated CI |612passed,0fail/error/skip |
| Python in integrated CI |252passed |
| API26/29 native, each |210cases:205passed,5explicitplatform/permission assumptions |
| API35/36 native, each |210cases:208passed,2permission assumptions; separate denial cycle2/2passed |
| Wear | JVM/lint/audits plus all five real manual/cache/surface contracts passed without skips |
| Native smoke, every phone API | Legacy upgrade/history, network recovery, Doze, genuine PIN-locked post-boot observation and unlock passed |
| Core JAR | Actual clean022/077 builds byte-identical |
| Heap | Real maximum-v3 roundtrip under128MiB passed |
| Local integration | Core30/phone611/Wear7, lint/detekt/debug+unsigned release assemblies passed before the additional dispatcher proof; focused dispatcher6/6 and finalCI612/612 |
| HA example | Actual YAML/Jinja template/action fixtures executed; no running HA installation claim |

API35/36 execute actual platform insets and pending RU-to-EN share recreation.
JUnit XML drives counts; runner assumption progress is not counted as extra tests.
SDK qualification uses official AOSP Android16 sources, documented in
[android16-compatibility.md](../android16-compatibility.md).

## Packaging and limits

Normal exact-tag fdroidserver2.4.5 readmeta/lint/build passed with scanning and no
force/skip. Its unsigned APK matches an independent clean Git-aware source build:
SHA256c3821b95cc184ea6020ca106242a60b126f3e77e1aefffc8c0f2455fcd507e07.
Production signature-transplant results belong to the versioned JSON verification.
Catalog/server acceptance remains separate; no submission was sent.

Physical OEM battery savings are unmeasured; a device validation protocol is included.
GitHub metadata reads succeeded but description/topics writes returned HTTP403
Resource not accessible by integration. Prepared metadata remains unapplied.

## Immutable unpublished source snapshot

Tagv1.20.0/source d38ebb0 remains unchanged and unpublished. Run39237727506 passed
its seven quality/native jobs and real signed1.10.47 upgrade. The latest-stable
path stopped before installing the new APK: freshly installed1.19.1 opened its
first-run guide with monitoring deliberately off. The fixture incorrectly assumed
automatic startup. Version1.20.1 adds bounded emulator-only preparation through the
predecessor's own guide/start controls, followed by the unchanged genuine observation
and history/settings gates. It never starts the new APK manually after upgrade.

Five helper regressions and both complete workflow-pair fixtures cover the fresh
onboarding path, no double toggle and data reset only between independent pairs.
The full Python suite now has252cases. A real1.19.1 APK was also started through UI
and produced a real committed observation locally after software-emulator warmup.
Earlier local SystemUI ANR/slow dump attempts remain failures, not cold-performance
passes. Product observation/compute/transport deadlines were unchanged.

## Retained implementation rulings

All rulings below were collected from the five SDD ledgers before any cleanup.
Detailed local ledgers/RED-GREEN/native/build logs are retained. These are completed
implementation decisions, not pending features or deferred review findings.

### [2026-10-18-compatibility-release](../superpowers/plans/2026-10-18-compatibility-release.md)

- Ruling: use existing isolated cloud checkout, no new worktree — setup skill and approved plan explicitly retain it — cost if wrong: no extra checkout boundary, mitigated by dedicated branch and clean initial status.
- Ruling: invalid newer observations evict unsent pending events only in JSONv2, which claims current freshness, while legacy TEXT/JSONv1 event retries retain their prior contract. Measured tracker baselines and active physical ownership stay unchanged. Cost if wrong: stale v2 sends or lost legacy retries; two new queue regressions plus unchanged legacy stale/unmeasured retry test verify both boundaries.
- Ruling: the cross-goal native read fixture retries only RATE_LIMITED with null extras within the original absolute12s deadline, rather than changing the production dispatcher or permitting borrowed cached state. Cost if wrong: a fixture could hide permanent busy; the fixed total deadline and unchanged final code/payload assertions fail that condition, while a new deterministic finish-barrier unit test proves actual physical ownership.
- Ruling: configure a fresh onboarding-capable predecessor through its own guide/start controls before its first real observation; never seed fake observations or re-enable the new APK after upgrade. Cost if wrong: a fixture could conceal failed migration; original90s fresh-observation/history/independent-preference assertions remain, and integration tests keep data resets only between pairs.

### [2026-10-18-target-power](../superpowers/plans/2026-10-18-target-power.md)

- Ruling: cloud skill helper scripts and writing-good-tests resource unavailable through provider; maintain per-plan briefs/BASE/tests/completion ledger directly with the specified format — avoids blocking authorized implementation on unavailable bookkeeping helpers — cost if wrong: manual bookkeeping may lack helper validation; final independent review checks the ledger.
- Task1: Ruling: Android persisted600s roundtrip is verified in Task2's native integration class together with the Android adapter — Task1's pure model/wire domain is covered here and Task2 owns platform storage — cost if wrong: storage regression surfaces in Task2 before feature/release completion; no native persistence pass claimed yet.
- Task2: Ruling: extend TargetServiceCommands/TargetServiceHealth with bounded active runtime interval map — configured60 alone would falsely report a600s wait stalled — cost if wrong: watchdog timing; dedicated regression checks600 without changing Telegram30. Map is cached-event based; no periodic battery sampling.

### [2026-10-18-custom-health](../superpowers/plans/2026-10-18-custom-health.md)

- Ruling: shared core enums are also consumed by independent Wear; include Wear JVM verification after core edits — cost if wrong: portable consumer compile regression.
- Ruling: carry a safe optional ProbeDetailCode through phone outcomes/report/snapshot/checkpoint and resolve it with Android resources in TargetStatusUi — existing DTO discarded core reason, making approved mismatch feedback impossible — cost if wrong: optional DP enum persistence needs legacy fallback/redaction checks; no secret text/wire field added.
- Ruling: private dialog never prefills criteria; blank URL+blank criteria preserves existing config, criteria with blank URL use current CP URL on worker, explicit new URL resets unspecified criteria, explicit clear-criteria control preserves URL — avoids accidental clearing when editing interval — cost if wrong: additional UI semantics require native assertions/documentation.
- Ruling: extend existing CP transaction's legacy endpoint slot to a bounded4096-byte validated private envelope, still accepting raw legacy HTTPS URLs — whole config must survive the same durable cuts — cost if wrong: shared legacy backup transaction compatibility; retain raw tests and add every-durable-cut envelope replay test.

### [2026-10-18-webhook-heartbeat](../superpowers/plans/2026-10-18-webhook-heartbeat.md)

- Ruling: heartbeat statuses respect existing filters: OK→RECOVERY,DOWN→UNREACHABLE,PARTIAL→PARTIAL,NO_NETWORK→NETWORK_LOST; NETWORK_RESTORED remains its actual transition event — honors same filters/no automatic send with empty filter without adding heartbeat to delivery-event enum — cost if wrong: explicit mapping needed in UI/docs; tested initial/filtered/recovery baseline.
- Ruling: an admitted v2 event also starts the per-goal heartbeat cooldown and removes only matching unsent measurement — that event already carries freshness and should not immediately duplicate itself as heartbeat — cost if wrong: next heartbeat waits five minutes after event; event rate limit/tracker remain independent. Failed admitted starts consume cooldown; rejected submits do not.
- Ruling: extract bounded candidate state into WebhookHeartbeatCandidates, leaving existing coordinator/gate as sole physical owner — four latest candidates and expiry share existing publication trigger with no retry timer — cost if wrong: verify synchronous callbacks,generation/forget,physical-slot and baseline tests.
- Ruling: add pinnedJinja2=3.1.6 plus MarkupSafe=3.0.4 to existing hashed Python tooling lock so CI executes the actual example templates — independently restating predicates would miss queue/template bugs — cost if wrong: two test/tool dependencies; existingsevenpins unchanged, allninehashes checked. No APK/runtime dependency added. FullPython execution pending below.

### [2026-10-18-resource-localization](../superpowers/plans/2026-10-18-resource-localization.md)

- Ruling: add TextArgument.Value(TextValue) for typed semantic composition and a precise flag on Duration argument — summaries/history contain nested target names and full recovery durations that cannot be expressed using only primitive text without pretranslating in pure code — cost if wrong: resolver recursion/immutable argument copies need verification. No globalContext or bilingual Kotlin catalog.
- Ruling: Context.renderString resolves typed TextValue args at Android boundary and leaves scalar values unchanged; Java bridge avoids formatting zero-argument literal strings — future typed age/duration values must not silently display TextValue.toString in UI — cost if wrong: explicit native nestedarg RU/EN assertion added. No globalContext, no language catalog.
- Ruling: TEXT WebhookBody carries optional immutable TextValue until Android admission, unlike JSON bytes; no Context enters coordinator/policy. Cost if wrong: unresolved payload could become empty; guarded transport admission plus native exact UTF8/body test and typed coordinator tests cover the boundary.
- Ruling: retain caller-supplied ProbeOutcome.detail String for injected/legacy technical fixtures, add optional typed detailText for actual structured adapter outcomes. Android diagnostics prefer detailText, core classification still uses enum detailCode; no Context in protocol DTO/computation and no old log rewriting. Cost if wrong: mixed callers might render an empty legacy detail; native line/resource tests plus existing primary publication/physical-slot suites verify actual adapter path.
- Ruling: persist only the non-secret canonical capture locale in Activity saved state while work is pending, so recreation during an app-language switch recaptures under the same requested locale. A completed document remains the existing retained immutable preview; explicit refresh chooses current resources. Cost if wrong: stale/pending locale could replace a user's explicit new capture; bind records document locale and retry chooses current configuration. No Activity retained by worker.

