# Local iOS validation — 2026-11-02

This change publishes only the connection-diagnosis and VPN-comparison policy helpers plus their focused tests. These files originated in the existing Xcode Coding Assistant work on `feat/ios-complete-experience`; they were inspected before publication. The base was `693b4633ab7e5f0918a9e07f871413c8b54a17be`.

The helpers classify missing/stale measurements, distinguish observed statuses, and compare availability without treating an offline/unknown sample as an improvement. They do not change VPN settings or provide a VPN service. The three focused tests cover freshness, status mapping, and comparison uncertainty.

## Verification

- Xcode 27.2 (27B52) on the owner's MacBook Air.
- Native package: **67 tests passed** (25 XCTest + 42 Swift Testing), zero failures. The package source tested is the same package source included by this commit; other uncommitted work was outside TgWatchCore.
- The larger, uncommitted local app snapshot also passed **5 iOS 27.2 Simulator UI tests** and a signed Debug build for physical iOS. These results do not assert that the unfinished feature set is complete or included in this commit.
- The signed local app was `ru.tgwatch.ios`, version 0.1.0, build 1. Deep/strict signature verification passed using existing provisioning profiles. No provisioning-update flags, new account login, security changes, or paid enrollment were used.

## Device outcome

One update-install attempt for the existing app identifier failed before device allocation with `Failed to allocate RSD device`, MobileDevice error `0xE8000003`. Bundle-scoped installed-app queries before and after the attempt timed out. Installation, installed version, and launch are therefore **not verified**. No uninstall or user-data deletion was performed. Device unlock/reconnection and a fresh read-only status check are required before retrying installation. If a trust, Developer Mode, signing, or capability change is required, obtain specific authorization first.

## Local work intentionally not published here

The existing working tree still contains unfinished Favorites/VPN UI integration, Widget and Watch scaffolding, App Group/project settings, and design/implementation notes. The Widget currently shows template content and the Watch app currently shows “Hello, world!”; snapshot transfer, stale-data handling, and encrypted backup are not implemented. They are not represented as completed features.

The two duplicate Watch targets had been removed through Xcode GUI with source files retained. The unused `TgWatchWatch Watch App` scheme was subsequently removed through Manage Schemes after saving a backup outside the repository. The valid companion target and embed relationship remain. These project edits remain local because they are interdependent with unfinished targets/capabilities. No `.pbxproj` was edited directly.

The local build still warns that the Widget version 1.0 differs from its containing iPhone app version 0.1.0. The companion also remains at version 1.0. Resolve version alignment in Xcode when completing those components.

Physical notifications/vibration, live Wi-Fi/cellular/VPN transitions, installed-data preservation across a successful update, and physical Watch behavior have not been tested. Raw logs, profiles, device identifiers, accounts, credentials, and build products are excluded from the commit.
