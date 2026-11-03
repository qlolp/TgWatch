# TgWatch iPhone MVP

Native SwiftUI foreground Telegram reachability monitor. Minimum deployment target iOS 17;
no Telegram account, message access, backend, APNs or background timer.
Local notification permission is requested only from the explicit Alerts screen.
MTProto greeting is unauthenticated reachability evidence, not proof of service identity or message delivery.
Web HTTP 100–499 is an endpoint response, not account availability. Partial/incomplete measurements
remain distinct from a confirmed negative result. A network transition invalidates outstanding results.

Open `ios/TgWatchIOS.xcodeproj`, scheme `TgWatchIOS`. SwiftPM dependency is the local
`ios/TgWatchCore` package; no CocoaPods, Fastlane or third-party runtime dependencies.
The local project has the owner's development team configured for physical-device builds.
Do not enable automatic account provisioning during simulator tests.

## Tests

Portable policies: `swift test --package-path ios/TgWatchCore`.
This runs classifier, MTProto codec, bounded scheduler, history and lifecycle tests on Linux/macOS.
On macOS it also runs Apple HTTP transport fixtures. Linux excludes Network.framework tests.

On a Mac with full Xcode and an already installed iPhone runtime:

```bash
xcodebuild -version
xcodebuild -showsdks
xcrun simctl list devices available
bash scripts/test-ios.sh SIMULATOR_UDID /tmp/tgwatch-ios-new-results
```

The script refuses a missing runtime or existing output directory. It runs SwiftPM tests,
Simulator UI tests and a Release simulator build with signing disabled. It never accepts licenses,
downloads runtimes, signs into an account or uses `-allowProvisioningUpdates`.
The isolated iOS workflow proposes macos-26/Xcode 26.6, based on the published runner inventory.
This does not establish compatibility with the owner's reported iOS 27.2.

UI fixtures are enabled only in DEBUG with `--fixture ok|offline|slow`; they use isolated ephemeral
history, disable live path observation and display a visible fixture banner. Release cannot select them.
Tests must never depend on a currently reachable public Telegram endpoint.

## Data and privacy

History envelope schema 2 is local to iOS: raw schema-1 observations retain the 7-day / 10,000-entry
limit. The same atomic file also holds up to 90 UTC daily totals, independent of the raw-entry cap.
The 8 MiB decode ceiling still applies. Legacy arrays migrate using all surviving records before
pruning; already deleted records cannot be recovered. Older app builds cannot read the new envelope.
Daily reports include today and the preceding 6/29/89 UTC days. Gaps are unknown; pending coverage
is capped at observation expiry, split at midnight, and never counted twice after reload. Rollback
starts a new aggregate segment. Clear/import replaces both raw observations and daily totals.
A corrupted file remains untouched until explicit Delete history. Failed writes keep bounded dirty entries
in memory for retry; the UI reports storage failure. No Android backup import is claimed.
New results expire after the selected check interval plus 15 seconds; legacy entries retain their
120-second lifetime. History entries are observations, not fabricated coverage while suspended.
Clock rollback starts a new segment. Returning from background never restores an old result as live.

## Monitoring and reports

The foreground monitor offers Economy (60 seconds), Balanced (30 seconds), and Frequent
(15 seconds) profiles, matching Android's awake intervals. The selected profile is saved locally
in `preferences.json`. iOS does not provide Android's continuous foreground service: checks stop
when the app becomes inactive.

Statistics cover the last 24 hours or 7 days: availability, observation coverage, check count,
outage count, longest outage, up to 100 recent episodes, mean recovery time and a state chart. Availability excludes offline and unknown time.
Outages and their duration are clipped to the report window; unknown gaps split episodes.
Mean recovery time includes only episodes with an observed beginning and uninterrupted recovery
to OK; partial status continues an episode, while offline/unknown time interrupts it. Clipped starts
and episodes without observed recovery are labeled explicitly. Pull to refresh updates the report window.
A separate 7/30/90-day report shows daily check counts, availability, downtime and unknown coverage.
Daily aggregates are not exported as fabricated CSV intervals.

CSV export uses Android's nine-column v3 format with public target ID, UTC intervals and measured
latency (`-1` when unavailable). Only the selected current revision/clock segment is exported,
with normalized portable revision 1 and epoch 0. Unknown gaps remain explicit.
Import validates a single target/current revision v3 file or Telegram's legacy five-column file,
shows a preview, and replaces only that history after confirmation. It rejects mixed targets,
archived revisions, inconsistent durations and files above 8 MiB / 10,000 observations.
Monitoring stays stopped after replacement. This is history transfer, not an app backup.

## Independent services

The Services tab includes Telegram, Matrix versions, WhatsApp Web and up to five custom HTTPS
targets. Each has an independent session, 10–300-second interval, history, alerts and reports.
The interval is a pause after check completion. No target starts monitoring automatically at launch.
Changing an address creates a new local revision and stops that target; old revisions remain on
disk, outside current statistics. Removing a custom target removes its local revision directory.
Private definitions are file-protected and excluded from system backups.

HTTPS targets accept 2xx by default, optionally an exact HTTP code, a case-sensitive body
substring (256 characters), and up to four JSON string conditions. Every configured condition
must match. JSON paths use up to three nested keys and 64 characters; expected strings may be
empty and are limited to 256 characters. Full JSON validation rejects duplicate keys (including
escaped equivalents), malformed UTF-8 and nesting beyond 10 containers.

Up to eight request headers can be configured, with case-insensitively unique ASCII token names
(64 bytes) and printable ASCII values (256 bytes). Host, Content-Length, Transfer-Encoding,
Connection and Accept-Encoding remain transport-owned. Headers are only sent to the target;
they are never forwarded to the internet control probe. Redirects are not followed.

Body or JSON conditions use one GET with identity encoding and a 32 KiB response cap. Unknown-length
responses reaching the cap are rejected; an exact-cap response with known length is allowed.
Without body criteria, HEAD falls back to GET on 405/501. Matrix always uses JSON GET.
A failed check is DOWN only if a control endpoint confirms internet access. Generic targets never
fabricate MTProto evidence. An HTTP/JSON success does not prove all service features work.

The custom-target editor includes GitHub, Discord, Cloudflare, Atlassian and OpenAI status-page
presets. Applying one explicitly replaces the form fields and clears headers, then requires Save.
Each preset checks HTTP 200 and status.indicator = none. Existing definitions with a single JSON
condition still decode; edits preserve their criteria. New criteria are optional fields in the
local manifest, excluded from CSV, sharing and webhook payloads. Older iOS builds do not understand
the new fields: do not downgrade a configured installation and expect equivalent checks.

Alerts independently select outage, partial availability, recovery and network loss. They share
an optional sound, minute-based quiet hours and an in-process one-hour mute. Gaps, suspension,
network changes and clock rollback reset recovery continuity. The event journal derives the last
150 state transitions from retained history. Latency reports show mean, p50 and p95 over an hour,
day or week; Telegram stores the fastest confirmed MTProto reply. Share status previews a fixed
public snapshot before opening the system share sheet.

Optional per-target webhooks support plain text and Android's JSON v1 eight-key allowlist;
network is currently UNKNOWN. Test events have null measurement fields. One request per target,
5-second request / 10-second resource deadline, no redirects, cookies or cache. In-flight events
are not queued and failures are not retried. Changing the recipient cancels the prior request and
prevents its result from overwriting the new recipient's delivery state. The address is private,
file-protected and excluded from system backups. No JSON v2 heartbeat is claimed.

## Slow-response alerts

Per-target opt-in latency warnings offer 500/1000/2000/5000 ms thresholds. Three consecutive OK
measurements at or above the threshold trigger a warning. Latency below 60% clears the warning;
a monotonic 30-minute cooldown prevents repeated alerts. Lifecycle, route, observation gaps and
settings changes reset the streak without resetting the cooldown. Missing latency cannot trigger an
alert. Quiet hours and temporary silence suppress sound using the existing alert policy. No
background latency monitoring or slow-response webhook delivery is implied.

## Public IP and exit country

The Telegram dashboard and Services tab share one foreground-only exit card: flag, localized
country name, public IP and check time. Like Android, the source is
[Cloudflare HTTPS trace](https://developers.cloudflare.com/fundamentals/reference/cdn-cgi-endpoint/).
Country comes from `loc`, never from the `colo` data-center code. IPv4 and IPv6 are accepted;
unknown country codes show a globe rather than a fabricated flag.

One ephemeral HTTPS request runs on foreground entry, network-path changes, manual refresh and
about every minute while active. A route change immediately invalidates the old result; generation
checks and cancellation reject late completions. Requests are debounced and at least five seconds
apart. No cookies, credentials, disk cache or redirects; 4 KiB maximum body and eight-second
resource timeout. Errors clear the previous result. The display expires after 90 seconds, including
when the wall clock moves backward. Fixture runs use a documentation IP and perform no lookup.

Cloudflare sees the source IP needed to answer the request. The app retains the returned IP only
in memory: no history, preferences, diagnostics, CSV, webhook or backup includes it. Country is
approximate IP geolocation for this HTTPS route, not GPS location or proof that all traffic uses a
VPN. Split routing, proxies and different IPv4/IPv6 paths can produce different exits for other
requests. This is not a VPN leak test and it does not change VPN configuration.

## Remaining Android differences

Continuous Android-style background execution is not implemented and is not claimed.
The daily summary is a local notification whose text was prepared while the app was open.
Widgets, Apple Watch snapshots and an iOS encrypted backup (not Android TGWB) are in the app.
History keeps raw checks for 7 days, minute buckets through 30 days and five-minute buckets
through 90 days. A v3 CSV can be imported for several known targets at once. Scheduled pauses
skip history rows and alerts without stopping the visible check. The report splits checks by
Wi-Fi, mobile, Ethernet or no network. Previous endpoint revisions can be listed read-only.
ntfy is a preset that only composes the existing webhook address.

Still absent: Android TGWB import, MQTT, Tasker/broadcast adapters, and a promise of
locked-screen monitoring. The iOS backup format is intentionally separate from TGWB.

No analytics, cookies, shared diagnostic logs or account identifiers. Privacy manifest declares no
tracking/collected data; current code does not use UserDefaults, file timestamp, disk-space or device-boot-time
APIs. Reassess required-reason declarations whenever these APIs are introduced. See Apple's
[required-reason API guidance](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api).

See [toolchain and verification](toolchain.md) and [device procedure](device-testing.md).
