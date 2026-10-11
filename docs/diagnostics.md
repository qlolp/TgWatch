# Local diagnostics and scheduling

Use **Settings → Export diagnostics** to choose a JSON file with Android's file
picker. Export is optional, stays at the destination you select, and is never
uploaded by TgWatch. A storage provider you select controls that destination.
The report is limited to 16 KiB and contains an explicit field allowlist:

- App version/code and Android API level.
- Notification permission, exact-alarm permission, battery exemption, device
  idle observation, power-saving mode, and unlocked state.
- Current status, last-check timestamp, freshness, and probe latency.
- Last 24-hour aggregate status durations, checks, outages, and availability.
- Last completed probe's known endpoint names, group, reachability, and latency.
- Scheduling counters for the latest service session.

The report excludes raw history, logs, settings, account information, passwords,
automation tokens, IP addresses, device identifiers, network names, and free-form
probe errors/reasons. Endpoint addresses are excluded, including the public
MTProto addresses. Endpoint names are restricted to the bundled known probes;
results are empty after restart/restore until a fresh probe completes.

## Scheduling observations

Periodic checks record their target and arrival with Android's monotonic elapsed
clock. A delay of more than one second counts as late; the report includes the
number of late arrivals, their total delay, and the largest delay. Alarm and
handler delivery consume the same target once. Manual requests and network
replacement retries cancel that target and do not count as late scheduled
arrivals. Arrivals during an active probe also count only as overlaps. Manual
request and overlapping request counts are separate; settings refreshes are not
manual check requests.

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
