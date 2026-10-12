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

## Monitoring and history

Choose an Economy, Balanced or Frequent battery profile, or a custom interval.
Background checks use a foreground service, alarms and a watchdog. Android Doze
and manufacturer restrictions can still delay checks; the app reports freshness
and observed scheduling delays. Notification permission and battery settings are
explained in the app.

Full outages, partial availability and recovery have independent notification
and sound settings. Quiet hours support minute precision and follow local time.
An optional daily summary reports the last 24 hours. The home-screen widget and
Quick Settings tile offer the current status and a manual check.

Seven-day history distinguishes measured intervals from gaps. Offline or unknown
time does not silently become a Telegram outage. Uptime is calculated by time;
check counts and mean recovery time are separate metrics.

## Backup, automation and diagnostics

The encrypted portable backup contains settings, history and the event log. New
exports use AES-256-GCM and PBKDF2-HMAC-SHA256 with 600,000 iterations. Keep the
password: there is no recovery service. Restoring replaces data and leaves
monitoring stopped. CSV export/import is also supported. See [backup formats](backup.md).

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
results are excluded from Telegram classification and alerts. A [webhook](automation.md#webhook-alert)
(for example an ntfy.sh topic) can receive one short line when the published
status changes. Its address is treated as a credential and kept out of backups
and diagnostics.

## Install and develop

[Latest published APK](https://github.com/qlolp/TgWatch/releases/latest/download/TgWatch.apk).
Development APKs use a different signing key and cannot replace a production
installation. Do not uninstall an existing app before exporting its data.

The source version is `1.13.1`, Android target API35. This states the source
version, not that a release has already been published. The release workflow
checks dependency audits, production certificate continuity and a real upgrade.

Use JDK17, Android SDK35 and the checked-in Gradle wrapper:

```sh
bash ./gradlew testDebugUnitTest detekt lintDebug assembleDebug assembleRelease
```

Local release builds are unsigned unless the existing production signing key is
configured. See [contributing](../CONTRIBUTING.md) and [F-Droid preparation](fdroid.md).
F-Droid acceptance and a verified reproducible production build are separate
steps. The code is licensed under [MIT](../LICENSE).
