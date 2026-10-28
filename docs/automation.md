# Automation API (versions 1 and 2)

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

The bearer token grants access to the read/check operations below. Keep it in a trusted
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
| `4` | Rate limited: CHECK_NOW within its 10-second budget, GET_STATE within the 200 ms compute gap without a fresh one-second cache, or a computation still holding the single physical slot (including another target); no extras. |

Result data is null. Only success includes these result extras:

| Field | Type | Meaning |
| --- | --- | --- |
| `api_version` | int | `1` |
| `status` | string | `UNKNOWN`, `OK`, `PARTIAL`, `TG_DOWN`, or `NO_NETWORK` |
| `checked_at_ms` | long | Unix milliseconds of the snapshot's check; `0` when unknown. |
| `stale` | boolean | True for stopped/unknown state, backwards wall time, or more than three expected check intervals without a result. |
| `running` | boolean | Telegram is enabled in the running monitor and no restore is pending. |

## Independent targets (version 2)

Use the same explicit component and bearer token, with one of these actions and a
string extra `target_id`: `telegram`, `matrix`, `whatsapp`, `custom`, or `custom2` …
`custom5` for added custom goals (a slot that was not added reports a stopped goal). The ID is
required and case sensitive; unknown or missing IDs return result `2` without state.

| Action | Behavior |
| --- | --- |
| `ru.tgwatch.automation.GET_TARGET_STATE` | Return only the chosen target's current/persisted public state. |
| `ru.tgwatch.automation.CHECK_TARGET_NOW` | Request a check of the chosen enabled target in the existing service. |

Version 2 returns the same five base fields, with `api_version=2`, plus `target_id`
and an optional nonnegative long `latency_ms` when a latency was measured. Its
status values are `UNKNOWN`, `OK`, `PARTIAL`, `DOWN`, and `NO_NETWORK`. Addresses,
credentials, raw probe errors and history are excluded. A stopped target has
`running=false` and `stale=true`, even when another target keeps the common service
running. A private target cannot be checked before unlock or during an accepted
endpoint change. No API request configures or enables a target.

The ten-second manual-check budget is independent per target and durable within
one boot. Version 1 and version 2 share Telegram's budget. Each target/version has
its own one-second response cache. All computations share one physical worker;
an eight-second response deadline finishes the ordered request without creating a
replacement worker while the old one remains blocked.

Legacy `CHECK_NOW` and `GET_STATE` always mean Telegram, regardless of extra
`target_id`, the selected screen or other enabled targets. They retain version 1,
the five-field response and `TG_DOWN`. In Tasker, choose the version-two action
and add `target_id:matrix` to request Matrix explicitly.

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

**Advanced → Automation and webhook** supports up to five independent recipients,
each with a user-owned HTTPS address. It is off by default. Existing single-webhook
settings remain recipient 1 in place, with their saved address, enablement, format,
and filters unchanged. Add and select recipients in the settings screen; each has
independent enablement, address, format, target/event filters, configuration
generation, and delivery health/retry state. Save an address, choose **Text**
(the default, suitable for an ntfy topic) or **JSON**, and select event filters
for each recipient. **Targets** selects Telegram, Matrix, WhatsApp Web and/or
Custom HTTPS goals. These filters only control delivery: they never enable a goal
or monitoring. Empty target/event filters suppress automatic events and heartbeats;
the explicit test button still works for the selected recipient.
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
One shared network worker serves every recipient. Its queue holds at most four
additional requests across all recipients, so at most five requests can be admitted
at once; each recipient can own only one admitted request at a time. Queue saturation
fails admission instead of creating unbounded work. A timeout reports once and
requests cancellation, but a stuck native/DNS operation keeps the worker occupied
until it actually returns. There is no backlog of old events: pending events are
coalesced per recipient/goal, and later valid monitor checks attempt the latest
allowed event. Retries retain a minimum 60-second gap per status per recipient;
NO_NETWORK delivery is best effort and normally cannot leave the device.

The private screen shows sanitized delivery health without the address, response
body or transport exception. URL, enabled switch, format, event and target filters
form one configuration generation per recipient. Saving any of them isolates that
recipient's old callbacks and rate/health history, including after a failed disk
commit that changed SharedPreferences memory. Changing settings does not free an
old physical request or send automatically. Removing a recipient clears its private
settings; the primary recipient cannot be removed.

All webhook configuration is in credential-protected `webhook-private-v1`, outside
Direct Boot, portable backup, platform backup/transfer, diagnostics and logs.
Before the first unlock the adapter is inactive. The screen displays only the saved
host; paths/queries may grant posting rights and should be treated as credentials.

### ntfy preset and native MQTT adapters (optional)

The HTTPS webhook does not cover brokers that speak only MQTT, or an ntfy server that needs
an access token without hand-written query parameters. Two opt-in adapters address that.
Neither changes a goal's measured status, wakes or starts monitoring, or enables anything by
itself; both only consume already published measurements, and both are off by default.

**ntfy preset** (Advanced → Automation and webhook → *ntfy preset*) is not a second network
worker. It composes an ordinary HTTPS webhook address for the selected recipient from a
server (empty means `https://ntfy.sh`; HTTPS, no path), a topic (1–64 letters, digits, `-`
or `_`) and an optional `tk_…` access token, which is sent as ntfy's `?auth=` query value.
It also selects the Text format. Delivery, five-recipient limit, one shared worker, queue,
ten-second deadline, retry gap and health are exactly those of the recipient. The topic and
token are credentials inside the saved address: stored only in `webhook-private-v1`, never
prefilled or displayed after saving (only the host is shown).

**Native MQTT** publishes to at most one broker:

- MQTT 3.1.1 over TLS only (default port 8883), certificate and host name verified against
  the system trust store; no plain TCP, no WebSocket, no user-installed bypass.
- Bounded settings: host ≤ 253 ASCII characters, port 1–65535, topic ≤ 128 bytes without
  `#`, `+`, control characters or a leading `$`, optional username ≤ 64 and password ≤ 128
  printable ASCII bytes. A password requires a username; a previously stored password-only
  configuration is treated as unconfigured. The payload is the generic JSON v1 body (under 1 KiB),
  QoS 1, not retained, with a random per-connection client ID.
- One short connection per message on one dedicated thread: connect, publish, wait for
  PUBACK, disconnect. There is no persistent socket, so no keep-alive timer or reconnect loop.
  Connect/read timeouts are five seconds each and the same ten-second total deadline as the
  webhook applies; the deadline closes the socket.
- Reconnect model: after a failed attempt new attempts are refused for 30 seconds, doubling
  per consecutive failure up to 15 minutes, and reset by success or by saving settings. A
  refused or failed attempt is reported as a failed delivery. There is **no offline queue**:
  events are coalesced to the latest measured state per goal (at most four), retried only
  when a later valid check publishes, and heartbeats are not sent.
- Event filters are all five events and all goals. Test messages work while disabled.

Broker, topic and credentials are stored only in credential-protected `mqtt-private-v1`,
excluded from platform backup/transfer, portable backups, diagnostics, logs, public replies
and saved view state; the screen shows only the host. Before the first unlock the adapter is
inactive. The coordinator sees only a placeholder address. Existing webhook recipients
(including the legacy first recipient) are unaffected.

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

The supplied packages explicitly select `service_id: telegram`. For another goal,
adapt that service-ID check and the helper/entity names to `matrix`, `whatsapp`,
`custom` or `custom2` … `custom5`; use separate entities for separate goals. A target filter in TgWatch
does not change the receiving package's service-ID validation.

Custom request headers remain a separate extension.

## Independent targets: API v2

The same explicit receiver and bearer-token checks also accept
`ru.tgwatch.automation.GET_TARGET_STATE` and
`ru.tgwatch.automation.CHECK_TARGET_NOW`, with a required `target_id` extra:
`telegram`, `matrix`, `whatsapp`, `custom`, or `custom2` … `custom5`. Missing/unknown IDs are rejected.
State replies use `api_version=2`, generic `DOWN`, `target_id`, the existing public
state fields and optional `latency_ms`; they contain no addresses or history.
CHECK operates only against the existing active target and never starts it.
Each target has its own durable ten-second check limit; Telegram shares that
limit between API v1 and v2. State requests share one physical compute slot and
an eight-second logical reply deadline. Expiry does not free blocked work.

API v1 and its exact historical reply fields continue to refer to Telegram.
When Telegram is disabled while another target keeps the service alive, v1
reports `running=false` and CHECK returns STOPPED.

Opt-in text/JSON webhooks use the supported `service_id` values. Baselines,
pending transitions and cooldowns are separate per target and per recipient, while
every recipient shares the same single physical HTTPS worker and bounded queue.
Stale/unmeasured results are ignored; settings changes invalidate only the affected
recipient's pending work. The JSON schema remains version 1 with the same eight
public fields.

### JSON v2 + heartbeat

Select **JSON v2 + heartbeat** explicitly to send fresh measured status while it
is stable, including the first OK. Text and JSON v1 remain event-only. No extra
phone alarm, probe or service is added: a new published measurement is the only
automatic delivery trigger. Each goal sends a heartbeat at most once per five minutes per recipient/goal; an
admitted v2 event supplies the same freshness and starts that cooldown.
Rejected transport submissions do not consume it. A failed admitted request does;
only a later suitable measurement can retry. All configured goals and recipients
share the existing one physical HTTPS worker and bounded queue. Each request keeps
its ten-second deadline. Events have priority, and a request that has not returned
continues to own its slot after logical timeout.

The existing event filters also restrict heartbeat statuses: OK follows Recovery,
DOWN follows Unreachable, PARTIAL follows Partial, and NO_NETWORK follows Network
lost. Network restored remains its transition event. Empty filters disable
automatic sends. Heartbeats never create recovery events or alter their previous
status. Disabling monitoring or losing the process does not promise a final post.

V2 contains exactly the eight v1 keys plus `sent_at_ms`, `monitoring` and
`is_stale`, with `schema_version: 2`. A heartbeat has `event: "heartbeat"` and
`previous_status: null`; `checked_at_ms` remains the source measurement time,
not the sending time. Accepted measurements have `monitoring: true` and
`is_stale: false`. An explicit test has no measurement/status/latency and instead
uses false/true, so it cannot make an availability sensor healthy. Addresses,
conditions, credentials, IPs, logs and raw history are excluded.

The [freshness-aware HA package](examples/home-assistant/tgwatch-heartbeat.yaml)
uses your authenticated user-owned HTTPS webhook ingress with a long random
`tgwatch_webhook_id` in HA secrets. It accepts only POST from local/VPN sources;
configure your trusted HTTPS reverse proxy accordingly. Its four status sensors
use persisted measurement/receipt helpers and restored 720-second HA timers.
The timers run on Home Assistant, with no additional phone schedule. The last
status can be DOWN, PARTIAL or NO_NETWORK while the sensor's measurement is
available; **unavailable** means its freshness cannot be confirmed.

Only a strictly newer `checked_at_ms` updates a goal. Duplicate or out-of-order
packets cannot replace its measurement or restart expiry. Ordering is checked
again inside the serialized action queue. Expiry uses HA receipt time, so an
absolute phone clock offset cannot keep a sensor live. A source-clock rollback
leaves the sensor unavailable until source order recovers or you explicitly
clear that goal's `input_text.tgwatch_<goal>` helper after verifying the source.
Treat that manual reset as establishing a new trusted stream. HA clock rollback
also invalidates a receipt age; restored helpers do not make expired data fresh.

Delivery remains best effort. The receiver's shared secret authenticates access,
not a cryptographic proof of a phone measurement. A first packet establishes the
ordering baseline; arbitrary initial network delay cannot be measured without
shared clocks. Existing JSON v1 users can keep the
[event-only example](examples/home-assistant/tgwatch-events.yaml), which never
claims continuous availability. Executable fixtures render the v2 package's Jinja
templates/actions and exercise expiry/replay; they do not run a full HA server.
