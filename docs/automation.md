# Automation API (version 1)

Open **Advanced → Automation**, enable access, and copy the access token.
Access is disabled by default. Each token contains 256 random bits encoded as
64 lowercase hexadecimal characters. Regenerate replaces the token immediately;
disable removes it, and re-enabling creates a fresh token. Update your tasks
after either operation. The settings screen prevents screenshots and automatic
view-state/autofill storage. The screen shows a mask and the token length; the
bearer token never enters displayed text or the accessibility tree. Copy is explicit, marks the clipboard sensitive on
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
| `ru.tgwatch.automation.GET_STATE` | Return the bounded current snapshot without starting a monitor or performing a network request. Fresh snapshots are built off the main thread; a one-second process-local cache serves polling clients without repeated prefs/journal reads. |

Use an **ordered broadcast** to receive the result. No implicit reply or state
broadcast is emitted by this API. An ordinary broadcast can request CHECK_NOW but
does not return its result. Results are synchronous; CHECK_NOW success means the
request was queued, not that a new network result is ready. The rate budget is
persisted only after the existing service accepts admission; a stopped or
unavailable handoff does not spend it. A failed durable write fails closed.
A stop or restore can
cancel a queued check. Poll GET_STATE later to observe freshness.

| Result code | Meaning |
| --- | --- |
| `0` | Authorized valid request; snapshot included. |
| `1` | Unauthorized or automation disabled; no extras. |
| `2` | Invalid action/component/input; no extras. |
| `3` | Monitoring stopped/disabled, restore pending, or check handoff unavailable; no extras. |
| `4` | Rate limited: CHECK_NOW within its 10-second budget, or GET_STATE recomputation within the 200 ms compute gap without a fresh one-second cache; no extras. |

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

## Webhook alerts and Home Assistant

**Advanced → Automation and webhook** sends optional status-change events to one
user-owned HTTPS address. It is off by default. Save an address, choose **Text**
(the default, suitable for an ntfy topic) or **JSON**, and select event filters.
**Send test message** also works while delivery is disabled; it never changes the
measured baseline and is not an observation.

Text remains one translated line, for example `TG Monitor: Telegram is unreachable`.
JSON is `application/json; charset=utf-8` with exactly this versioned allowlist:

```json
{
  "schema_version": 1,
  "service_id": "telegram",
  "event": "unreachable",
  "status": "DOWN",
  "previous_status": "OK",
  "checked_at_ms": 1792038600000,
  "latency_ms": null,
  "network": "WIFI"
}
```

- `status`: OK, PARTIAL, DOWN, NO_NETWORK. Legacy Telegram TG_DOWN maps to DOWN
  only in JSON; existing API v1 and text behavior remain compatible.
- `event`: unreachable, partial, network_lost, recovery, network_restored. Recovery
  means OK after DOWN/PARTIAL; a measured return from NO_NETWORK is network_restored.
  All five are selected by default. Filters never change observations or local alerts.
- `previous_status` is the previous measured state, independently of filtering,
  delivery success and retries. It is null on an initial failure. UNKNOWN,
  cancelled, unmeasured and stale results neither send events nor reset this baseline.
  An initial OK is silent. Repeated delivered status is not a heartbeat.
- `checked_at_ms` is the completed check's Unix timestamp; `latency_ms` is its
  confirmed latency or null (never -1). `network` is WIFI, CELLULAR, VPN, ETHERNET,
  OTHER or UNKNOWN; SSIDs, IP addresses and Android network handles are excluded.
- An explicit JSON test uses event=test, service_id=telegram, network=UNKNOWN;
  status, previous_status, checked_at_ms and latency_ms are null. Tests bypass
  event filters and do not affect recovery detection.

Generic JSON is **not** the ntfy JSON API: ntfy uses a different request schema.
Use Text for an ntfy topic. No history, free-form target names, raw diagnostic
errors, endpoint addresses or credentials are included in either body.

Only HTTPS URLs up to 512 characters without user info or fragment are accepted;
query strings (for example ntfy options) are allowed. Redirects are not followed.
The User-Agent is `TgWatch/<installed version> (Android)`. Connect/read timeouts
are five seconds each, with an independent ten-second total monotonic deadline.
One physical request is allowed in the whole process. A timeout reports once and
requests cancellation, but a stuck native/DNS operation keeps the slot until it
actually returns. There is no backlog of old events: later valid monitor checks
attempt the latest allowed event. Retries retain a minimum 60-second gap per status;
NO_NETWORK delivery is best effort and normally cannot leave the device.

The private screen shows sanitized delivery health without the address, response
body or transport exception. URL, enabled switch, format and filters form one
configuration generation. Saving any of them isolates old callbacks and rate/health
history, including after a failed disk commit that changed SharedPreferences memory.
Changing settings does not free an old physical request or send automatically.

All webhook configuration is in credential-protected `webhook-private-v1`, outside
Direct Boot, portable backup, platform backup/transfer, diagnostics and logs.
Before the first unlock the adapter is inactive. The screen displays only the saved
host; paths/queries may grant posting rights and should be treated as credentials.

### Home Assistant package

Copy [tgwatch-events.yaml](examples/home-assistant/tgwatch-events.yaml) into your HA
packages setup. Put a long random `tgwatch_webhook_id` in HA `secrets.yaml`, expose
the corresponding `/api/webhook/<secret>` through your own HTTPS endpoint and
configure TgWatch for JSON. The example is local-only: the phone must reach it
through the permitted local/VPN network and trusted HTTPS ingress. Adapt HA network
policy explicitly if you need remote delivery. Do not paste the secret URL into issues.

The package records **Last event** and its measurement time. It has no permanent
online sensor: event-only delivery has no heartbeat, and a saved OK may be stale.
Missing messages never prove continued availability. Explicit test events, unknown
statuses, wrong service IDs and missing timestamps do not replace the helpers.
The fixture test checks YAML structure, mappings and the documented payload contract;
it does not certify a running Home Assistant instance or render every HA template.

Native MQTT, multiple destinations and custom headers remain separate extensions.
