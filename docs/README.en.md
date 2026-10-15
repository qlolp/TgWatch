# TgWatch

[Russian documentation](../README.md)

TgWatch monitors Telegram reachability locally on Android 8.0 and later. It needs
no Telegram account, phone number or access to messages. The interface supports
English and Russian; Android 13+ also provides a per-app language setting.

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
monitoring stopped. Exports from 1.13.2 use JSON payload version 2 inside the
unchanged TGWB v2 envelope and require TgWatch 1.13.2 or later to restore. Older
supported backups remain readable. CSV export/import is also supported.
See [backup formats](backup.md).

[Automation](automation.md) is disabled by default. An explicit broadcast API
allows authorized Tasker/Termux clients to read a bounded status snapshot or
request a check from an already running monitor. Every request needs the private
capability token generated in the app. Tokens are not included in backups.

[Local diagnostics](diagnostics.md) can be saved explicitly through Android's
file picker. The report contains aggregate status and power/scheduling data;
it excludes raw history, logs and automation credentials. Nothing is uploaded
automatically.

Optional, off by default: informational HTTPS checks of Matrix, WhatsApp Web and
one custom `https://` address appear only in connection diagnostics. Their
results are excluded from Telegram classification and alerts, and use three
separate workers so a stuck extra probe cannot occupy Telegram/control workers.
Telegram's result is published first; later EXTRA results only enrich diagnostics
for the same check and network.
A [webhook](automation.md#webhook-alert)
(for example an ntfy.sh topic) can receive one short line when the published
status changes. Its address is treated as a credential and kept out of backups
and diagnostics. Address changes invalidate stale send decisions; an older
request may finish without changing the new address's delivery history.
Webhook results have a 10-second total deadline. Cancellation is requested at
expiry, while a noncooperative transport retains the single physical slot.
The private settings screen shows sanitized delivery health for the current
process, without exposing or exporting the address or exception details.

## Install and develop

[Latest published APK](https://github.com/qlolp/TgWatch/releases/latest/download/TgWatch.apk).
Development APKs use a different signing key and cannot replace a production
installation. Do not uninstall an existing app before exporting its data.

The source version is `1.18.0` (Android code `1018000`), Android target API35.
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

Use JDK17, Android SDK35 and the checked-in Gradle wrapper:

```sh
bash ./gradlew testDebugUnitTest detekt lintDebug assembleDebug assembleRelease
```

Local release builds are unsigned unless the existing production signing key is
configured. See [contributing](../CONTRIBUTING.md) and [F-Droid preparation](fdroid.md).
F-Droid acceptance and a verified reproducible production build are separate
steps. The code is licensed under [MIT](../LICENSE).
