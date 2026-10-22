# TgWatch

[Russian documentation](../README.md)

TgWatch monitors Telegram reachability locally on Android 8.0 and later. It needs
no Telegram account, phone number or access to messages. The interface supports
English and Russian; Android 13+ also provides a per-app language setting.

Optional independent targets cover Matrix, WhatsApp Web and a custom HTTPS service,
each with its own history, intervals and alerts. Telegram remains the default;
additional targets and integrations start disabled. See [targets](multiservice.md).

Optional regional profiles cover YouTube, Gemini, Notebook, Antigravity and ChatGPT/dots.
Version 1.23.0 adds a seven-day local journal, fresh A/B connection comparisons,
factual explanations and separately labelled user-reported browser/feature checks.
Backup lifecycle, bounded statistics and probe matching fixes from 1.22.2 are retained.
An opt-in, one-shot watch can notify after two fresh public responses at least
15 seconds apart, with a 24-hour deadline. Blocked delivery does not consume it.
Public-site access and country information do not certify account features or an
IP-only restriction. Regional records remain outside portable backups and diagnostics.
See the [regional-services guide](regional-services.md), [native Mac app](macos.md)
and [release notes](releases/1.23.0.md). Mac builds require macOS13+ and Python3.10+;
local universal builds are ad-hoc signed, without Developer ID or notarization.

## Reading the status

| Status | Meaning |
| --- | --- |
| Reachable | Both a Telegram web endpoint and a structurally valid MTProto greeting responded. |
| Partially reachable | Only the web or MTProto signal responded. |
| Telegram unavailable | A control site responded, but Telegram did not confirm reachability. |
| No network | No active connection, or the network needs sign-in. |
| Unknown | The probes cannot establish availability. This does not prove an internet outage or blocking. |
| Monitoring delayed | The last observation is stale. An alive process is not proof of fresh monitoring. |

The MTProto probe performs only `req_pq_multi` / `resPQ`, with a random nonce and
strict bounded parsing. It does not authenticate the server, create an account
session, or prove that messages can be delivered. A system VPN affects the probes;
Telegram's own configured proxy does not.

Each of the five production DCs has IPv4 and IPv6 probes. Either address family
can establish the MTProto signal. A bounded pool admits all thirteen primary
requests; optional informational checks retain their separate workers.

## Monitoring and history

Choose an Economy, Balanced or Frequent battery profile, or a custom interval.
Background checks use a foreground service, alarms and a watchdog. Android Doze
and manufacturer restrictions can still delay checks; the app reports freshness
and observed scheduling delays. Notification permission and battery settings are
explained in the app.

Full outages, partial availability and recovery have independent notification
and sound settings. Quiet hours support minute precision and follow local time.
Outage and partial alerts offer "Check now" and "Mute for 1 hour"; a mute
silences alert sound and vibration like quiet hours and stays on this device.
The timer follows elapsed time across clock corrections and process restarts;
a device reboot ends it. "Resume alerts" cancels the timer while preserving quiet
hours, event preferences and Android notification channels.
An optional daily summary reports the last 24 hours while monitoring is on.
The home-screen widget and Quick Settings tile offer the current status and a
manual check.

Seven-day history distinguishes measured intervals from gaps. Offline or unknown
time does not silently become a Telegram outage. Uptime is calculated by time;
check counts and mean recovery time are separate metrics. Clock-epoch metadata
is excluded from check counts and the chart. A temporary storage write failure
is repaired by saving the complete retained snapshot on the next successful write.
History preparation is cached; the main screen loads statistics and chart buckets
off the UI thread. Incident cards list the latest 100 episodes in the selected
day/week window, distinguish outage/partial/offline/unknown time, and mark clipped
starts and unobserved recovery. Explicit sharing exports only episode times and
duration totals. See [1.15.0](releases/1.15.0.md).

## Backup, automation and diagnostics

The encrypted portable backup contains settings, history and the event log. New
exports use AES-256-GCM and PBKDF2-HMAC-SHA256 with 600,000 iterations. Keep the
password: there is no recovery service. Restoring replaces data and leaves
monitoring stopped. Current all-target exports use TGWB v3 with JSON payload
version 3. A separate Telegram-only v2 export supports older installations;
private addresses are excluded from both new export formats. Older supported
backups remain readable. CSV export/import is also supported.
See [backup formats](backup.md).

[Automation](automation.md) is disabled by default. An explicit broadcast API
allows authorized Tasker/Termux clients to read a bounded status snapshot or
request a check from an already running monitor. Every request needs the private
capability token generated in the app. Tokens are not included in backups.

[Local diagnostics](diagnostics.md) can be saved explicitly through Android's
file picker. The report contains aggregate status and power/scheduling data;
it excludes raw history, logs and automation credentials. Nothing is uploaded
automatically.

Independent Matrix/WhatsApp/custom HTTPS targets do not change Telegram status.
Legacy informational EXTRA checks remain diagnostics only and never automatically
enable a target. Private service addresses, the automation token and webhook URL
stay outside portable backups, Direct Boot data and diagnostic/share exports.

The optional [HTTPS webhook](automation.md#webhook-alert) supports text or a bounded
JSON event with per-target status filters. The JSON contains public status, previous
status, measurement time, latency and network class, without endpoints or tokens.
One physical request, a ten-second deadline, rate limits and settings-generation
fences apply. The [Home Assistant example](examples/home-assistant/tgwatch-events.yaml)
uses events, without a heartbeat or MQTT client in the app.

## Latency and surfaces

The selected target's latency screen offers hour/day/week counts, mean and exact
nearest-rank p50/p95. Telegram uses the best confirmed MTProto response per check;
local DC/address-family histograms provide approximate ranges, not message speed.
Configurable slow-response alerts require consecutive measured results. Unknown or
unmeasured checks break the pending streak. DC buckets are not portable backup data.

Widgets select a target; existing widgets keep Telegram. An expanded widget shows
six hours of history, observation coverage, last success and check/mute actions.
Enlarged fonts keep the status, graph and48dp actions readable; secondary captions
remain available in the full TalkBack description and the goal screen.
Status sharing freezes a preview before sending either text or a PNG through the
system share sheet. Both use the same capture, excluding addresses and secrets.

The standalone `ru.tgwatch.wear` APK for Wear OS 3+/API30 measures this watch's network,
without a phone or Google Data Layer. Manual checks use one web endpoint and three
rotating DCs, at most four physical requests and eight seconds. A failed subset does
not claim a global outage. Tile and complication read only the local cache, expire
after three minutes and open a user-initiated check on tap. No periodic background
monitor runs on the watch. [Wear details](wear.md).

## Install and develop

[Latest published phone APK](https://github.com/qlolp/TgWatch/releases/latest/download/TgWatch.apk)
and [SHA-256](https://github.com/qlolp/TgWatch/releases/latest/download/TgWatch.apk.sha256).
The source version and a completed build do not establish publication: inspect the
[release page](https://github.com/qlolp/TgWatch/releases/latest). Wear assets use the
separate `TgWatch-wear` prefix; every unified release includes both apps' six files.
Development APKs use a different signing key and cannot replace a production
installation. Do not uninstall an existing app before exporting its data.

The source version is `1.23.0` (Android code `1023000`), phone target API36, Wear target API35.
A short first-run guide explains what is checked and asks for notifications and battery
access; on Android 12+ the screens use Material You colors. See [1.18.0](releases/1.18.0.md).
Version 1.17.2 is built with Gradle 8.14.6, AGP 8.13.2 and Kotlin 2.4.21, and a check whose network changed
mid-probe is no longer recorded. See [1.17.2](releases/1.17.2.md).
The persistent notification and widget redraw only when their content changes, and the
check alarm no longer fires needlessly while the screen is on. See [1.17.1](releases/1.17.1.md).
`GET_STATE` serves a one-second cache and builds fresh snapshots off the main thread
([1.17.0](releases/1.17.0.md)). 1.16.0 kept interrupted probes, background statistics,
masked tokens and explicit blocked webhook delivery ([1.16.0](releases/1.16.0.md)).
Published versions and signed APKs are recorded on
[GitHub Releases](https://github.com/qlolp/TgWatch/releases/latest). The release workflow
checks dependency audits, production certificate continuity and a real upgrade.

Use JDK17, Android SDK36 (phone), SDK35 (Wear) and the checked-in Gradle wrapper:

```sh
bash ./gradlew :probe-core:test :app:testDebugUnitTest :app:detekt :app:lintDebug :app:assembleDebug :wear:testDebugUnitTest :wear:lintDebug :wear:assembleDebug
```

Local release builds are unsigned unless the existing production signing key is
configured. See [contributing](../CONTRIBUTING.md) and [F-Droid preparation](fdroid.md).
F-Droid acceptance and a verified reproducible production build are separate
steps. The code is licensed under [MIT](../LICENSE).

Custom HTTPS optionally checks an exact HTTP code and top-level JSON string (for
example `status=ok`). These criteria remain private and are excluded from backups
and exports. See [health criteria and limits](multiservice.md#custom-https-health-criteria).

The explicit [JSON v2 + heartbeat](automation.md#json-v2--heartbeat) format sends
fresh measurements without new phone alarms. Its Home Assistant package expires
measurement freshness after twelve minutes; replayed packets cannot extend it.
