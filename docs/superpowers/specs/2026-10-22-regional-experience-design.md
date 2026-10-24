# Regional experience: six sequential improvements

The user approved implementing all six proposed directions, in order, using the necessary tools. The intended outcome is a personal availability assistant on Android and Mac: understand observations, compare connections and notice a useful change. Explicit service errors remain evidence of what the service said, never proof of an IP-only cause or working account features.

## Shared constraints

- Android min26/target36; Wear target35. Existing four TargetIds, portable backup formats, automation/webhook contracts, primary probe budgets and permanent signing certificate remain compatible.
- Fixed five regional services; optional monitoring stays off by default. No account access, cookies, page export, raw response/IP storage, automatic VPN switching or new telemetry.
- Every current observation must match the active network and configuration generation. Persisted historical records are always historical; process recreation cannot make them current.
- Native Android Views with 48dp controls, scrolling/insets, resource-based RU/EN strings and 200% font support. Bounded I/O and expensive preparation stay off the main thread.
- Regional data is device-local, outside existing portable backups/diagnostics. Restore clears it and advances a fence so queued writes cannot resurrect it. Explicit clear is available.
- Use pinned existing build tooling and quality/signing/upgrade gates. Next release is 1.23.0/code1023000. Published tags/assets are immutable.

## 1. Regional event history

Maintain a local timeline of public-result transitions, connection-context changes, country observations and explicitly supplied verification results. Repeated identical observations in one context do not fill the journal. A changed HTTP code, confirmation state, failure category or country is meaningful. Record country observation time separately; Cloudflare's country is context rather than the service's proven exit location.

The journal holds at most 1,000 entries for seven days, at most 512KiB encoded. Its strict versioned codec accepts only fixed enums, ISO codes, nonnegative timestamps and a random opaque 32-hex connection token. Network handles, addresses and IP hashes never reach disk. Clock rollback does not reorder old observations into a claimed causal sequence; the UI labels the wall time of each event and reports unknown order where appropriate. Failed I/O leaves the previous durable snapshot intact and produces an explicit storage-error state.

Pure `RegionalJournalEntry`, `RegionalJournalCodec` and `RegionalJournalStorage` own validation, deduplication, retention and atomic durable replacement. The Android `RegionalRepository` owns one bounded background I/O worker, cached immutable snapshots and write-generation fencing. `RegionalContext` keeps raw network identity only in process memory, minting a new random context on network/configuration changes. The history screen displays newest events, source/age and a clear action; it never interprets correlation as causation.

## 2. Fresh manual connection comparison

An Android comparison flow records A, asks the user to switch their own connection if desired, then records B. The user can name each side with at most 40 characters, excluding controls; names stay in the comparison file. Starting a side requests fresh observations for the enabled profiles. A collector accepts only post-start results for one captured connection/configuration; a context change invalidates the unfinished side. A 75-second deadline produces explicitly incomplete measurements, never inferred failures. At least one regional profile must be enabled.

`RegionalComparisonCollector` consumes observations and produces an immutable `RegionalComparisonSnapshot`. `RegionalComparisons` stores at most ten completed pairs with bounded validated fields and durable replacement. Pending work is process-local. Saved pairs display result-by-result comparisons, measurement time, route category and country/source age. Neither side is called a universally better VPN or a proof of account readiness. The same connection may be measured twice; the UI says when it was not changed.

## 3. Factual explanation

`RegionalExplanation` maps typed public results to semantic explanation/action IDs. Android resources provide the copy. For example, authentication means a response was received and sign-in/consent is required; it does not mean a regional block. DNS/TLS/timeouts identify the measured failure stage and do not establish failures in stages that were never reached. A generic 403/451 remains unclassified. Explicit regional evidence links to connection comparison and manual verification.

Each service offers a “Why?” dialog with the observed result, its age/source, the specific safe explanation and a relevant next action. Normal public responses link to a feature test; no extra network requests or AI service are needed to explain existing facts.

## 4. Separate verification layers

Present three separate sources: automatic public-site observation, a browser-helper result explicitly reported by the user, and a feature outcome explicitly reported by the user. Opening a service is not a successful test. A normal page in the browser helper still does not confirm account functionality.

`RegionalVerification` validates a fixed layer/outcome pair, service, context and measurement time. Browser choices are regional error, sign-in, challenge or no explicit error; feature choices are works, failed or regional error. The UI always labels both as user-reported. Both enter historical events, never public-site consensus or automatic regional alerts. They remain current for at most 15 minutes in the same live process/network/configuration context; historical notes survive restart but are not current proof. Users can clear a note. No free-form page/error text is collected.

## 5. Native Mac menu-bar application

Create an AppKit menu-bar application (macOS13+) around the existing independently measured Python companion. Its menu selects the five profiles, starts/stops monitoring, requests a fresh check, opens the fixed service URLs, shows result/country/time and links to help. Notifications and launch at login are explicit opt-ins. No open Terminal window is needed. The existing public CLI JSON schema remains unchanged.

A separate `--app-json` mode provides a strict bounded IPC schema: fixed service/status, HTTP code, country, timestamp, confirmation and a random context token. Internal route/egress hashes remain in the Python process. Profile changes, stop/restart and replaced children fence buffered output. SIGTERM closes owned probe children. A pure Foundation model validates lines and ignores unknown/malformed/oversized/stale data; UI and subprocess callbacks are marshalled to the main thread.

The app bundles the first-party Python script and requires Python3.10+. It finds a compatible installed interpreter without a shell and provides a clear missing-runtime message. A double-click build command creates the `.app` with the local Apple compiler; reproducible build instructions and CI compilation/model tests accompany it. Apple Developer ID/notarization is not available from the Android signing key and must not be claimed. Local app builds use ad-hoc signing; never disable Gatekeeper or install a background agent silently. Distribution must state these prerequisites and signing limits explicitly.

## 6. One-shot public-response watch

An enabled service with a confirmed regional refusal can arm “Tell me when the public site responds”. This is an explicit one-shot request, not an account-success notification. Two fresh WEB_REACHABLE observations at least 15 seconds apart in one current context satisfy it. Network/configuration changes reset positive evidence; unknown, auth, CAPTCHA, rate limiting and network failures cannot complete it. The watch expires after 24 hours, is cancelled on profile disable/restore, and never consumes itself while notifications are blocked, muted, in quiet hours or maintenance. A first positive schedules a confirming observation.

Android stores only the armed metadata (boot identity, elapsed deadline and profile generation); positive evidence stays in memory and restarts after process death. A reboot resumes only the remaining time: wall-clock anchors are re-aligned to the elapsed deadline on every process start and clock change, so a manual clock change cannot stretch the 24 hours; a clock still behind the arming time after boot waits for a later sync instead of dropping the request; a disabled or re-enabled profile, missing anchors or an expired deadline end it. The resumed state is shown in the UI, and pre-boot evidence is never reused. Existing monitoring/scheduling/wake budgets remain shared; no additional foreground service. Notification copy states “Public website responds; account features remain unverified”. Delivery clears the armed request only after successful posting, with stable notification identity and suppression of repeated alerts.

The Mac menu offers the same one-shot goal with a 24-hour expiry. It uses independently confirmed IPC observations and native notification permission. Stop/profile changes reset evidence; disabled profiles cancel watches. Local preference persistence retains armed intent, not old positive evidence. Both platforms expose cancel/armed state.

## Acceptance and delivery

Each of the six tasks has tests observed failing before implementation and passing afterward. Meaningful pure codec/policy tests cover malformed input, bounds, scope/clock changes and cancellation; Android integration covers real persistence, restore, UI sources/accessibility and notification permission/quiet-hours behavior. Python/Node existing suites remain green. Mac pure model/process/build tests and a real local app build prove packaging; interactive menu/notification and login behavior have explicit smoke evidence or an honest limitation.

One fresh whole-branch review follows the six implementation tasks. Important findings get a reproducing test and correction. Full Android26/29/35/36 and Wear gates, dependency audits, immutable1.10.47 plus latest-stable signed upgrades and downloaded APK signature/version/hash verification remain publication requirements. The sequential implementation, decisions and exact validation evidence are tracked in the task ledger.
