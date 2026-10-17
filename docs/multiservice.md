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
