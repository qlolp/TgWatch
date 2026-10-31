# Android → iPhone parity, 2026-10-31

Compared actual Android v1.27.0 (`8932ef0`) with iPhone PR100 baseline `0300f1e` and `docs/development-roadmap.md`. The user's latest priority is Telegram connection-loss alerts, then outgoing-IP country and a clearer UI. This slice implements those priorities; it does not claim all Android functions have been ported.

| Actual Android feature / source | iPhone in this slice | Next work / constraints |
|---|---|---|
| Telegram MTProto/Web/control evidence; `MonitorService` alerts | Existing probes preserved; two fresh consecutive observations confirm loss/recovery; separate no-network alert; opt-in local notifications; 30-second foreground cadence | Device acceptance below. Android foreground-service/Direct Boot monitoring cannot be replicated by an ordinary iOS app |
| `EgressCountry`, `CountryLookupState` | Same Cloudflare trace source; flag + localized country name + checked time; unknown/expired states; invalidation on network/scene/clock changes | Country is HTTPS egress estimate, not Telegram routing or GPS |
| Android dashboard | SwiftUI connection/country/alert/evidence cards, adaptive colours/text, history always reachable; original app icon | Simulator screenshots and real-device readability remain distinct checks |
| `TargetDefinition`, `TargetEndpoints`: Telegram, Matrix, WhatsApp, five Custom HTTPS slots | Telegram only | Next functional slice: fixed targets, per-target history/settings. Matrix needs complete valid `versions` JSON; WhatsApp response does not prove account availability. Custom URLs need bounded validation/secret storage |
| `IncidentCenter`, `TargetHistories`, `TargetCsv`: incidents, 7/30/90-day rollups/export | Existing 7-day bounded observation history; unknown gaps preserved | Next: incident grouping with gap/epoch boundaries, public CSV, schema migration tests |
| `RegionalService`, `RegionalClassifier`: five regional profiles with explicit evidence | Not ported | Separate from outage classification: generic 403/200 cannot prove region restriction/availability; preserve auth/rate/unknown distinctions |
| Local diagnostics, target intervals, alert/quiet-hour settings | Basic probe evidence; fixed 30-second foreground interval; notification preference | Later bounded diagnostics/export and validated settings |
| Encrypted backup, MQTT/TLS, Home Assistant, webhooks, ntfy, Wear | Not ported | Independent scopes, privacy and credentials decisions required |
| Widgets | Country shown in app with checked time | Widget accepted as follow-up; must display observation age/unknown state, never imply continuous monitoring |

Roadmap proposals such as Room/DI migration, NTP and remotely supplied DC JSON are not existing Android features to copy. F-Droid, physical OEM testing and Mac notarization are separate external work, not iPhone acceptance.

## Alert acceptance contract

- Opt-in enables foreground monitoring. System permission is requested explicitly and shown as allowed, quiet, denied, not requested, or fixture. Disabling monitoring also disables alerts. Focus/sound settings can suppress presentation even when authorization is granted.
- Two distinct, consecutive observations in the same epoch, no more than 120 seconds apart, confirm an event. Telegram failure requires completed primary probes plus a successful control; unavailable path produces **NO_NETWORK**, never a Telegram-server outage. UNKNOWN/PARTIAL interrupt confirmation and cannot confirm recovery.
- No initial “recovered” notification. Subsequent identical events are deduplicated; two OK observations after a reported failure confirm current recovery. A gap is not evidence of when recovery occurred. Changing route/clock resets confirmation; backgrounding or permission/opt-in changes reset the policy.
- Late measurements cannot publish after route/scene cancellation. Notification submissions have unique ownership, are removed after invalidation even if submission finishes late, and foreground presentation rechecks eligibility. A notification already handed to iOS is a timestamped observation, not a continuing guarantee; a banner already displayed cannot be retracted from human memory.

| Scenario | Expected result / acceptance |
|---|---|
| App active, alert opt-in and authorization, two complete Telegram failures with control OK | One Telegram-loss notification; third identical failure does not repeat |
| App active, path unavailable twice | One no-network notification; Telegram availability remains unknown |
| Partial / incomplete / unknown result, one isolated failure, duplicate observation | No confirmed-loss/recovery event |
| Two fresh OK checks after reported loss | One recovery notification, timestamped |
| Disable alerts, leave app, change route/clock while submission is pending | Obsolete pending/delivered request removed; obsolete foreground presentation rejected |
| Locked, backgrounded, terminated or force-quit | No monitoring guarantee and no new local probe alerts; return requires fresh checks |
| Notifications denied / Focus / quiet delivery | Permission state shown; no audible/banner guarantee |

Device acceptance is still required: on the owner's iPhone, grant/deny permission, verify foreground loss via controlled network conditions, restore connectivity, verify dedupe, background/lock/return, and check the displayed time. Do not alter network settings remotely or claim simulated fixtures establish this acceptance.

## Country and privacy

`https://www.cloudflare.com/cdn-cgi/trace` is the same public source used by Android. Cloudflare necessarily sees the request source IP. The app does not persist or log the returned IP/trace; it retains only a validated ISO country in memory. GET is cookie/cache-free, rejects redirects/non-2xx, caps response at 8 KiB and uses a 3-second deadline. Invalid/ambiguous/missing country is unknown. A single country lookup is separate from the probe engine's four-connection budget.

Country is cleared on scene/network/clock invalidation and refreshed when active and connected; manual refresh clears previous confidence. A result expires after six hours using monotonic age and wall-clock consistency. Refresh is not a continuous location service. No paid provider, GPS, backend, APNs or shared upload was added.

## Platform limits and status bar

[Apple DTS](https://developer.apple.com/forums/thread/685525) explains that iOS has no general-purpose continuous background execution or guaranteed periodic schedule. [Background strategies](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app) do not turn the app into a daemon. A remote server measures its own route, not this iPhone's Telegram path; APNs cannot instantly deliver while the phone has no internet. No VPN extension workaround is proposed.

The public [UIStatusBarManager API](https://developer.apple.com/documentation/uikit/uistatusbarmanager) exposes configuration/style/visibility, not arbitrary third-party flag icons; this is why the flag is in the app. A widget can show a timestamped snapshot in a future slice. [Live Activities](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities) are finite (up to eight hours), not a permanent status-bar indicator or independent network monitor. Dynamic Island is assessed only; no extension/capabilities/provisioning changes are introduced.

## Verification

Portable tests cover alert transitions, gaps/epochs/duplicates, delayed notification invalidation/eligibility, country parsing and lifecycle stale responses, plus existing probe/history semantics. Apple native fixtures additionally exercise country GET/non-2xx/redirect/oversize/TLS behavior. UI fixtures check localized country, permission state, history/delete, offline diagnostics and largest Dynamic Type; they never request permission or use live endpoints. Simulator CI and physical-device acceptance must be reported separately.

Original icon source: `ios/Design/AppIcon.svg`; opaque 1024×1024 RGB asset in `Assets.xcassets/AppIcon.appiconset`. It depicts reachability waves and a signal trace, without Telegram branding. Render with CairoSVG, then convert to RGB; render dependencies are development-only and not shipped.
