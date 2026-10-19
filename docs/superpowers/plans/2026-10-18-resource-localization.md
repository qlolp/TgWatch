# Resource Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all phone user-visible text resource-based while preserving RU/EN behavior and a frozen share preview.
**Architecture:** Android presentation boundaries resolve resources. Pure decisions and measurements carry typed values rather than translated strings; no global Context, duplicated bilingual catalog or new UI stack.
**Tech Stack:** Kotlin, Android Views/resources/plurals, existing JVM/instrumentation/lint.
**Spec:** `docs/superpowers/specs/2026-10-18-app-completion-design.md`, section E.

## Global Constraints

- Preserve current RU/EN copy and default English resource fallback; no extra languages promised.
- Preserve API26, Direct Boot, worker-side history aggregation and current privacy allowlists.
- No global Context, Activity references, production-only test catalogs or bilingual Kotlin text.
- Numbered placeholders; quantity resources where grammar depends on count.
- Wire identifiers, URL/schema keys, file names and technical exception class names stay literal.
- Do not rewrite persisted historical log entries into the current language.
- Run after power/HTTPS/heartbeat so their new UI strings are included in the final scan.

## Review Focus

- A locale change while share preparation is pending cannot produce different preview/text/PNG labels.
- Direct Boot background notifications resolve resources before MainActivity has ever opened.
- Formatting large numbers, duration0, one/many plurals and percent99.96 preserves intended values.
- Removed JVM text assertions are replaced by typed-result assertions, not simply deleted.
- Private endpoint/condition strings cannot be interpolated into public sharing during refactoring.

### Task 1: Typed presentation data and common formatting

**Files:** Create `app/src/main/java/ru/tgwatch/TextValue.kt`, `AndroidText.kt`, `DurationParts.kt`; modify `MonitorService.kt` formatting helpers, `DailySummary.kt`, `History.kt`, `LastSuccessPolicy.kt`, `PowerProfile.kt`, `AlarmPattern.kt`; modify their callers and existing JVM tests; create `TextValueTest.kt`, `TextResourcesIntegrationTest.kt`.
**Interfaces:** `TextKey` enumerates semantic presentation keys only, with no language strings. `TextValue(key: TextKey,args: List<TextArgument>)`; sealed TextArgument variants `Number(Long)`, `Decimal(Double)`, `Text(String)`, `Duration(DurationParts)`. `DurationParts` holds nonnegative day/hour/minute/second values; no language. `AndroidText.resolve(ctx: Context,value: TextValue): String` maps keys to resource IDs and resolves arguments using ctx configuration; `AndroidText.quantity(ctx,key,count,args): String` resolves quantity resources. No Context stored between calls.

- [x] Write typed policy assertions for duration0/5000/135minutes and daily-summary no-outage/ongoing/missing-history cases. Add native resource assertions for exact existing RU/EN output, numbered placeholders and plural counts0/1/2/5/21. Existing LangTest expectations move to native resource tests, while JVM tests verify keys/values and stats.
- [x] Run focused JVM/native tests and observe RED for missing presentation model, then implement model/resolver and resource mappings. Keep computation worker-side; resolving resource text does not trigger storage/network.
- [x] Replace pure-policy translated return values with TextValue/typed duration results; move percent/date locale selection to Android presentation. Update every caller in the same commit so no half-migrated API is exposed.
- [x] Run all phone JVM tests and native RU/EN resource tests plus lint. Confirm zero duplicate resource names and matching placeholders. Verify boundary values use the same underlying measurements.
- [x] Commit `refactor: resolve common presentation through Android resources`.

### Task 2: Settings, notifications, widgets and diagnostics

**Files:** Modify all remaining `Lang.t` callers under `app/src/main/java/ru/tgwatch`, grouped by settings/dialogs, notifications/surfaces, history/diagnostics; `PhoneProbeAdapter.kt` resolves structured probe details at Android boundary; `Lang.kt` is removed when empty. Modify `values/strings.xml`, `values-ru/strings.xml`; corresponding JVM/native tests. Update CONTRIBUTING language rules.
**Interfaces:** Android classes use ctx.getString or AndroidText.resolve, never a global language switch. Pure probe/core remains Android-independent. Typed status/action enums are the source of resource selection. No localization API is added to portable backup/automation schema.

- [ ] Before each group, add native assertions for visible text under RU/EN and pure typed-output tests for corresponding policies. Include app startup without MainActivity, notification/Tile/widget paths and activity recreation. Run targeted tests to verify the missing resource path fails.
- [ ] Move one complete caller group at a time, add exact RU/EN resources, replace interpolated bilingual strings with numbered arguments. Preserve multiline formatting and accessibility descriptions. Do not suppress lint or regenerate detekt baseline to hide issues.
- [ ] After each group run complete JVM suite and relevant native tests. Final `rg 'Lang\.t|Lang\.ru|fun t\(ru: String' app/src/main/java` must find no production bilingual resolver. Resource lint must pass; historical persisted strings remain unchanged.
- [ ] Commit each complete group independently: `refactor: localize settings with resources`, `refactor: localize notifications and surfaces`, `refactor: localize history and diagnostics`.

### Task 3: Frozen share locale and large-font acceptance

**Files:** Modify `StatusShareSnapshot.kt`, `StatusCardRenderer.kt`, `StatusShareJobs.kt`, `StatusShareActivity.kt`; create `StatusShareDocument.kt`; update `StatusCardRendererTest.kt`, `StatusShareIntegrationTest.kt`, resource locale tests, docs.
**Interfaces:** Internal `StatusShareDocument.prepare(ctx: Context,snapshot: StatusShareSnapshot): StatusShareDocument` freezes immutable labels under the capture locale. Document exposes snapshot and read-only labels, has no public arbitrary-label constructor. Renderer text/PNG and preview consume this same document; no live Context/state lookup during export. Remove the russian boolean from the internal snapshot model after migrating its callers/tests; retain a captured BCP47 locale tag for formatting. No snapshot persistence/wire migration is needed.

- [ ] Write native test: capture RU preview, change system/activity locale to EN, export text and PNG; all labels remain captured RU and identical to preview. A second capture uses EN. Assert typed allowlist still excludes endpoint, conditions, logs and token. Add200% font, TalkBack and activity recreation checks for modified screens.
- [ ] Run targeted tests to observe RED for live/binary-language formatting, then freeze resource labels during worker preparation and share the document across all output paths.
- [ ] Run full JVM/native privacy/share suites, lint and resource validation. Assert PNG bounds1024x768, cache expiry/read grants and existing no-IO-on-main behavior remain. Compare captures to actual rendered labels rather than only prepared DTOs.
- [ ] Commit `refactor: freeze resource-based share labels at capture`.
