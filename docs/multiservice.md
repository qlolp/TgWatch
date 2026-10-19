# Independent monitoring targets

Telegram remains the default. Select Matrix, WhatsApp (web), or Custom HTTPS on
the main screen, open **Target settings**, then explicitly enable that target.
Each has its own status, interval, history, incident list, outage/recovery alerts,
slow-response threshold and temporary mute. Quiet hours and the older global mute
apply to every target. A first successful check does not create a recovery alert.

Matrix's public default checks `/_matrix/client/versions`; a custom Matrix base
address is optional. WhatsApp measures its public website, not messaging delivery.
Custom HTTPS accepts an HTTPS address without credentials, query or fragment.
An unsuccessful target check becomes DOWN only when a control probe confirms
internet access. Unknown or unmeasured results do not prove an outage.

Private addresses live in credential-protected storage. They are unavailable
before unlock and excluded from backups, diagnostics, automation and sharing.
A restored private target requires address review and remains off. Changing an
address starts a new endpoint revision; previous revisions remain archived and
do not contribute to the new address's statistics. Device clock rollback also
separates statistics into clock epochs.

Existing EXTRA diagnostics do not enable independent targets during upgrade.
When a diagnostic is enabled, the corresponding target's settings offer an
explicit conversion button. Custom HTTPS copies the old address only on the
settings worker after that choice; it stays private. Conversion disables the old
diagnostic after saving the independent target. Equivalent diagnostic probes are
suppressed while an independent target runs; a different custom diagnostic stays
available. No history is invented from earlier diagnostics.

One foreground service schedules all enabled targets, with shared physical
connection limits (16 primary, 3 secondary). Other targets use 60–300 seconds;
Telegram retains its profiles and 10–120 second custom interval. Disabling one
target keeps the others running. The historical Telegram widget, quick settings
tile, shortcuts and API v1 always refer to Telegram, including when only Matrix
keeps the common service alive.

## CSV v3

The main screen exports the selected target and time window, captured before the
file picker opens. The UTF-8 header is:

```text
from_utc,until_utc,status,duration_ms,latency_ms,clock_epoch,in_current_statistics,target_id,endpoint_revision
```

`target_id` is one of `telegram`, `matrix`, `whatsapp`, `custom`. Status uses generic
`DOWN`. Endpoint revisions and clock epochs remain separate; only the current
revision's current epoch has `in_current_statistics=true`. Zero-width UNKNOWN
metadata preserves identity without inventing a check. Unknown gaps remain
unobserved time. Private addresses never enter the CSV.

Import validates the complete document before stopping monitoring. It replaces
only histories explicitly named in the document, retains settings, log and other
histories, and leaves every target off for manual restart. Only imported private
targets require renewed endpoint review. The old five- and seven-column CSVs
still import into Telegram. Limits: 16 MiB / 98,000 rows for v3, at most 65,000
Telegram rows and 11,000 for each other target; legacy CSV retains its 8 MiB limit.

History charts, incidents, CSV, daily summaries and diagnostics keep targets
separate. There is no combined cross-service availability percentage. Availability
excludes offline/unknown time; observation coverage describes those gaps separately.

## Optional secondary-target battery saving

The device option is off by default and never enables a target. With it enabled,
Matrix, WhatsApp and CUSTOM wait at least 300 seconds after completion while the
screen is off, or 600 seconds in system battery saver / at 15% charge or lower while
unplugged. Charging removes only the low-charge restriction. Telegram profiles
and the selected secondary intervals of 60–300 seconds are unchanged. Target settings
show the selected/effective interval and its cause. Manual checks and network
changes retain their existing admission rules; no additional alarm is introduced.

Fresh observations, history coverage and watchdog timing use the effective
interval. Changing power state does not extend a previous history row. The option
is excluded from backups and is off after restore. See the [measurement procedure](power-validation.md)
for matched physical-device tests; emulator checks do not establish a battery-saving percentage.

## Custom HTTPS health criteria

CUSTOM uses HEAD and accepts any 2xx response by default; GET is used only when
HEAD returns 405 or 501. Optional criteria in **Target settings** can instead
require one code from 200–599 and/or a top-level JSON string. For example, field
`status` with expected value `ok` accepts `{"status":"ok"}` and rejects
`{"status":"error"}` even when both responses have HTTP 200. Empty expected strings
are allowed; the field name is 1–64 characters and the value at most 256 characters.
Comparison is exact and case-sensitive; there is no JSONPath or scripting.

JSON mode uses one GET with `Accept: application/json` and identity encoding,
strict UTF-8, at most 32 KiB, depth at most 10, and full JSON validation including
nested duplicate-key rejection. Missing/nonstring fields, truncated, oversized,
compressed or malformed responses fail confirmation. Unknown-length responses
that reach the cap are rejected; a complete, known-length response exactly at
the cap is allowed. Content never overrides the required HTTP code. TLS,
redirect restrictions, timeouts and shared connection limits are unchanged.

Saved criteria are never prefilled. Blank inputs preserve the saved configuration
when changing an interval. Entering a new address replaces its criteria; select
**Clear health criteria; keep address** to return to 2xx without changing the URL.
Entering criteria with a blank address changes the current private address's
criteria. URL and criteria form one private transaction and endpoint revision:
an older in-flight response cannot update the new configuration's status/history.

The URL, JSON field and expected value are credential-protected and excluded
from Direct Boot, both backup formats, CSV, diagnostics, sharing, webhook and
logs. The screen reports only a safe mismatch/DNS/TLS/timeout category; response
bodies and expected values are never error text. Existing backup/restore readers
remain supported and a restored private target remains stopped pending review.
A successful health response does not prove message delivery. Matrix, WhatsApp
and Telegram checks retain their previous behavior.
