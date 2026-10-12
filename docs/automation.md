# Automation API (version 1)

Open **Advanced → Automation**, enable access, and copy the access token.
Access is disabled by default. Each token contains 256 random bits encoded as
64 lowercase hexadecimal characters. Regenerate replaces the token immediately;
disable removes it, and re-enabling creates a fresh token. Update your tasks
after either operation. The settings screen prevents screenshots and automatic
view-state/autofill storage. Copy is explicit, marks the clipboard sensitive on
supported Android versions, and attempts to clear the same token after 45 seconds
or when access is disabled/regenerated. Background clipboard restrictions can
prevent that cleanup; Android 13+ also expires clipboard entries itself.

The bearer token grants access to the two operations below. Keep it in a trusted
automation app's private variables. Do not put it into task exports, logs, shared
files, URLs, screenshots, or public bug reports. It lives in ordinary private
credential-protected SharedPreferences, outside Direct Boot storage, portable
backups, and diagnostic exports. Android app backup is disabled. The API is
unavailable before device unlock.

Send an **explicit broadcast** to component
`ru.tgwatch/ru.tgwatch.AutomationReceiver`, with a string extra `token` containing
the exact token. Package-only and implicit intents are unsupported. There is no
signature permission, so separately signed clients such as Tasker can use it.
Every request is authenticated; a missing, invalid, revoked, or disabled token
returns no state. No request enables monitoring, changes settings, reads history,
or starts a stopped service. GET_STATE only reads a current/persisted snapshot.

| Action | Behavior |
| --- | --- |
| `ru.tgwatch.automation.CHECK_NOW` | Queue a manual check on the existing enabled monitor. At most one request per 10 seconds of monotonic elapsed time, including process recreation within one boot. Stop and restore guards are checked again on the worker. |
| `ru.tgwatch.automation.GET_STATE` | Return the bounded current snapshot without starting a monitor or performing a network request. |

Use an **ordered broadcast** to receive the result. No implicit reply or state
broadcast is emitted by this API. An ordinary broadcast can request CHECK_NOW but
does not return its result. Results are synchronous; CHECK_NOW success means the
request was queued, not that a new network result is ready. A stop or restore can
cancel a queued check. Poll GET_STATE later to observe freshness.

| Result code | Meaning |
| --- | --- |
| `0` | Authorized valid request; snapshot included. |
| `1` | Unauthorized or automation disabled; no extras. |
| `2` | Invalid action/component/input; no extras. |
| `3` | Monitoring stopped/disabled, restore pending, or check handoff unavailable; no extras. |
| `4` | CHECK_NOW rate limited; no extras. |

Result data is null. Only success includes these result extras:

| Field | Type | Meaning |
| --- | --- | --- |
| `api_version` | int | `1` |
| `status` | string | `UNKNOWN`, `OK`, `PARTIAL`, `TG_DOWN`, or `NO_NETWORK` |
| `checked_at_ms` | long | Unix milliseconds of the snapshot's check; `0` when unknown. |
| `stale` | boolean | True for stopped/unknown state, backwards wall time, or more than three expected check intervals without a result. |
| `running` | boolean | Monitor is running, enabled, and no restore is pending. |

## Tasker

Create a private Tasker variable `%TGWATCH_TOKEN` containing the copied token.
Add **System → Send Intent** with these exact fields:

```text
Action: ru.tgwatch.automation.CHECK_NOW
Cat: None
Mime Type: (empty)
Data: (empty)
Extra: token:%TGWATCH_TOKEN
Package: ru.tgwatch
Class: ru.tgwatch.AutomationReceiver
Target: Broadcast Receiver
```

Leave the remaining extras empty. Start monitoring in TgWatch first. Tasker's
standard Send Intent action sends an ordinary broadcast; it cannot inspect the
ordered result. To receive GET_STATE in a native Android automation integration,
use `Context.sendOrderedBroadcast` with your own final `BroadcastReceiver` and
read `getResultCode()` / `getResultExtras(false)`. This requires no permission.

## Termux through Android wireless debugging

Android restricts direct ActivityManager shell commands from ordinary app UIDs
on recent releases. The following works through authorized ADB wireless
debugging, without root or a signature permission. Android 11+ provides wireless
debugging in Developer options; turn it on, pair with the pairing address/port,
then connect with the separate debugging address/port shown by Android.

```sh
pkg install android-tools
adb pair 127.0.0.1:PAIRING_PORT
adb connect 127.0.0.1:DEBUGGING_PORT
read -r -s -p 'TgWatch token: ' TGWATCH_TOKEN
printf '\n'
adb shell am broadcast --user 0 \
  -n ru.tgwatch/ru.tgwatch.AutomationReceiver \
  -a ru.tgwatch.automation.GET_STATE --es token "$TGWATCH_TOKEN"
adb shell am broadcast --user 0 \
  -n ru.tgwatch/ru.tgwatch.AutomationReceiver \
  -a ru.tgwatch.automation.CHECK_NOW --es token "$TGWATCH_TOKEN"
unset TGWATCH_TOKEN
```

Replace the port placeholders with the current numbers and, if necessary, use
the device address shown by Android instead of loopback. For a secondary Android
user, replace `--user 0` with the user where TgWatch is installed. `am broadcast`
is ordered and prints the result code and extras. The token variable avoids
embedding its value in shell history; command arguments remain visible to the
authorized debugging environment while the command runs. This ADB example does
not require weakening the receiver's authentication. Disable wireless debugging
when finished if it is only needed for setup/testing.

## Webhook alert

**Advanced → Automation and webhook** can also post one short plain-text line,
for example `TG Monitor: Telegram is unreachable`, to an HTTPS address you own,
such as an ntfy.sh topic, when the published status changes. It is disabled by
default and needs a saved address before it can be enabled. **Send test message**
posts a fixed test line, even while the switch is off.

- Only `https://` addresses up to 512 characters, without user info or a
  fragment, are accepted; a query string (ntfy options) is allowed. Redirects
  are not followed. The body is `text/plain; charset=utf-8`; the User-Agent is
  `TgWatch/<installed version> (Android)`.
- The body is one status line in the interface language: no history,
  diagnostics, endpoint results, addresses or tokens.
- Reported statuses: OK, PARTIAL, TG_DOWN and NO_NETWORK. UNKNOWN is never sent,
  and a monitor that starts in OK stays silent until the first change. The
  first post of each status is immediate; the same status is posted again at
  most once per 60 seconds. A post held back by that limit, or one that failed
  (no 2xx response), is retried by a later check, so the endpoint converges to
  the current status. Delivery is best effort: a NO_NETWORK post usually cannot
  leave the device.
- One background thread, at most one request in flight, 5-second connect and
  read timeouts. Posting never blocks or changes monitoring, notifications or
  history. These are per-operation timeouts, not a total HTTP deadline. The
  decision state is per process.
- Saving an address or changing the enabled setting invalidates older send
  snapshots atomically. A request already admitted to the old address may finish,
  but its completion cannot update the new address's delivery/rate history or
  release another request. The old request continues to occupy the single
  physical slot until it finishes; a later monitor check can then send the same
  outage to the current address. A settings change does not send a new request
  automatically.
- Treat the address as a credential: anyone who knows an ntfy topic URL can post
  to it. Like the automation token it lives in private credential-protected
  SharedPreferences (`webhook-private-v1`), outside Direct Boot storage, portable
  backups, platform backup/transfer and diagnostic exports, and it is never
  logged. The screen shows only the host; enter the address again to replace it.
  Before the first unlock after a reboot the webhook is inactive.
