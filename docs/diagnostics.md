# Local diagnostics and scheduling

Use **Settings → Export diagnostics** to choose a JSON file with Android's file
picker. Export is optional, stays at the destination you select, and is never
uploaded by TgWatch. A storage provider you select controls that destination.
The report is limited to 16 KiB and contains an explicit field allowlist:

- App version/code and Android API level.
- Notification permission, exact-alarm permission, battery exemption, device
  idle observation, power-saving mode, and unlocked state.
- Current status, last-check timestamp, freshness, and probe latency.
- Last 24-hour aggregate status durations, checks, outages, availability and assessment coverage.
- Cached monitor health: current check start, unstarted probes, occupied physical slots,
  typed storage failure and recovery state.
- Last completed probe's known endpoint names, group, reachability, and latency.
- Scheduling counters for the latest service session.
- A bounded crash journal: the newest five uncaught exceptions as exception class,
  crashing thread name and up to 24 stack frames. Exception messages are never stored,
  so endpoints, tokens and document paths cannot reach the report. Records live in
  device-protected storage, rotate by count and byte budget, and are cleared with
  **Clear the log**.

The report excludes raw history, logs, settings, account information, passwords,
automation tokens, IP addresses, device identifiers, network names, and free-form
probe errors/reasons. Endpoint addresses are excluded, including the public
MTProto addresses. Endpoint names are restricted to the bundled known probes;
results are empty after restart/restore until a fresh probe completes.

Optional informational probes of other services (Matrix, WhatsApp Web, one
custom HTTPS address) use a separate `EXTRA` group. They appear only in the
in-app connection diagnostics, under their own heading, and are excluded from
this report. Their results are excluded from Telegram classification, latency,
reason and alerts. They run with a five-second overall budget on three separate
bounded workers, with no buffered queue. Telegram/control probes retain their own
sixteen workers, accommodating the thirteen IPv4/IPv6 Telegram endpoints and
three controls. A stuck extra transport cannot occupy that pool; the service
reuses both pools and shuts them down when it is destroyed. Transport cancellation
still depends on the underlying operation responding to interruption or closure.

## Availability, coverage and storage health

The main screen calculates availability and coverage over the same selected day or
week and history revision. Availability is OK / (OK + DOWN + PARTIAL); assessment
coverage is (OK + DOWN + PARTIAL) / the full selected window. Offline and
unobserved durations are shown separately. Twenty minutes of OK in one day means
100% observed availability with only 20/1440 of the day assessed. No assessments
mean insufficient data, rather than 0% or 100% availability. Current clock epochs
are used so a clock rollback cannot double-count overlapping observations.

Diagnostic schema version 2 adds `monitor_health` and `coverage_percent`,
`assessed_ms`, `window_ms` to `last_24_hours`. On a typed history read/write
failure, `last_24_hours` is null and `monitor_health.storage_failure` identifies
READ, WRITE, REPAIR or BACKPRESSURE without exception text. The measured network
state remains unchanged. The UI health ticker consumes cached counters without
waiting for the history disk monitor. Occupancy includes physical transports that
have not returned after logical cancellation; it does not claim those requests
were completed or that Doze caused their delay.

## Scheduling observations

Periodic checks record their target and arrival with Android's monotonic elapsed
clock. A delay of more than one second counts as late; the report includes the
number of late arrivals, their total delay, and the largest delay. Alarm and
handler delivery consume the same target once. Manual requests and network
replacement retries cancel that target and do not count as late scheduled
arrivals. Arrivals during an active probe also count only as overlaps. Manual
request and overlapping request counts are separate; settings refreshes are not
manual check requests. A manual or settings request during an active probe is not
replaced by the normal interval: all such requests merge into one immediate
follow-up check. An alarm during a probe is satisfied by that probe. Repeated
capability updates of the same validated network do not invalidate a result;
a new network, its loss, or a validation/captive-portal change does.

`idle_observed_arrivals` records whether Android reported device idle at the
arrival. It does **not** establish that Doze caused a delay. The operating system,
CPU load, power management, and an in-progress bounded probe can all affect
arrival. Counters reset when a new service instance starts; after stopping, the
last session counters remain available until process exit or restore.

## Notification availability

The ongoing notification displays native progress from last-24-hour TimeStats.
Its percentage uses reachable time divided by reachable + partial + Telegram
outage time. Offline and unknown time are excluded. Unknown/offline-only history
has no progress indicator; confirmed outage-only history displays zero.
Zero-width clock-epoch metadata is excluded from check counts and the chart;
real positive-width UNKNOWN observations remain checks.
Statistics are cached until the history version or current minute changes,
avoiding repeated history work on health ticks.

## Automation check ownership

`MonitorService.requestRunningCheck(Context)` only queues to the currently active
service worker. It returns false if monitoring is disabled, absent, stopping, or
restoring. A queued request rechecks those conditions and instance ownership
before scheduling work; stopping or restoring can invalidate it. No service
start occurs through this helper. The automation receiver owns its independent
10-second monotonic request limit; acceptance means queued, not a guarantee of a
completed probe.

## Multiple targets (schema 3)

Schema 3 retains the earlier Telegram sections and adds a bounded `targets` array
with fixed public IDs. Each entry has `target_id`, `running`, `status`,
`checked_at_ms`, `stale`, `latency_ms`, `last_24_hours`, and `storage_failure`.
A failed history read produces null aggregates and a typed failure, never zero
availability. No target address, free-form label, credential or raw history is
included. The existing `state.running` refers specifically to Telegram.

## Local crash journal (schema 4)

The explicit report adds `crashes.retained` and `crashes.last` (or null).
At most five local exceptions are retained. Messages are never stored. Thread names
are reduced to fixed app worker roles or `other`. Classes must resolve without
initialization in allowed app/platform namespaces; fabricated method names are
redacted. Source filenames must match their declaring class; paths and unknown
names become `redacted`. This validation applies to old journal entries and again
at the JSON export boundary. The journal is cleared with the event log and is not
uploaded automatically. A separate remote report is sent only when its collector
and explicit consent are configured as described below.

## Optional remote crash reporting

Remote reports are disabled by default and require both a separately saved HTTPS
collector address and an explicit consent switch. The collector is operated by
you; TgWatch has no crash-reporting operator or third-party telemetry SDK. Your
collector must require authentication. Its region, retention period and any DPA
or other data-processing terms are your responsibility; the app does not set or
control them.

After an uncaught crash, at most one best-effort POST is attempted in that
process. It has a 2.5-second total wait deadline and 1.5-second connect/read
timeouts. There is no offline queue, retry, startup request or monitoring request;
failed sends are discarded. Turning consent off disables sending immediately.
Remove deletes both the saved endpoint and consent. The endpoint is stored in
credential-protected private preferences, shown only as a host, and excluded
from backup, diagnostic exports, logs and saved view state.

Schema version 1 is bounded to 8 KiB and contains only the app version, Android
SDK level, a validated technical crash class and up to 24 sanitized first-party
stack frames. It has no endpoint, webhook URL, token, IP address, exception
message, user data, target criteria, target history or timestamp. The collector
necessarily sees the HTTPS request's source IP at the transport layer; this is
not part of the JSON payload.
If the endpoint path itself is the collector's authentication capability, configure
the collector not to retain that path in access logs.

## Private HTTPS health failures

A custom target can display a safe HTTP-code mismatch, unconfirmed JSON, DNS,
TLS or timeout category. The HTTP response body, required JSON field/value and
address stay private; none enter the diagnostic export or portable history.
See [custom health criteria](multiservice.md#custom-https-health-criteria).
