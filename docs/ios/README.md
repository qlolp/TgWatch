# TgWatch iPhone MVP

Native SwiftUI foreground Telegram reachability monitor. Minimum deployment target iOS 17;
no Telegram account, message access, backend, APNs, background timer or notification permission.
MTProto greeting is unauthenticated reachability evidence, not proof of service identity or message delivery.
Web HTTP 100–499 is an endpoint response, not account availability. Partial/incomplete measurements
remain distinct from a confirmed negative result. A network transition invalidates outstanding results.

Open `ios/TgWatchIOS.xcodeproj`, scheme `TgWatchIOS`. SwiftPM dependency is the local
`ios/TgWatchCore` package; no CocoaPods, Fastlane or third-party runtime dependencies.
Development team is deliberately unset. Do not enable automatic account provisioning during simulator tests.

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

History schema 1 is local to iOS: 7 days / 10,000 entries, 8 MiB decode ceiling, atomic replacement.
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
outage count, longest outage and a state chart. Availability excludes offline and unknown time.
Outages and their duration are clipped to the report window; unknown gaps split episodes.
Pull to refresh updates the report window.

CSV export uses Android's five-column format
`from_utc,until_utc,status,duration_ms,latency_ms`, with UTC timestamps and `-1` for unmeasured
latency. It exports the selected report window, including unknown gaps, rather than an app backup.
Custom targets, background alerts, widgets and encrypted backup import are not yet implemented.

No analytics, cookies, shared diagnostic logs or account identifiers. Privacy manifest declares no
tracking/collected data; current code does not use UserDefaults, file timestamp, disk-space or device-boot-time
APIs. Reassess required-reason declarations whenever these APIs are introduced. See Apple's
[required-reason API guidance](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api).

See [toolchain and verification](toolchain.md) and [device procedure](device-testing.md).
