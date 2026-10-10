# TG Watch 1.7 — reliability and diagnostics

Implement the user's approved review recommendations in the existing Android 8+ app.

- Keep the native Kotlin/View UI and local-only data; no Telegram login or message access.
- Distinguish UNKNOWN, OK, PARTIAL, TG_DOWN, NO_NETWORK. OK requires both a website and a nonce-validated MTProto greeting. These are reachability checks, never proof of delivery or authenticated server identity.
- Concurrent bounded probes with cancellation, network identity validation and diagnostic results (Wi-Fi/mobile/VPN, endpoint, time, error). Never infer no internet from an unfinished set of controls.
- Monitor freshness independently of the worker and restart a stalled check with bounded resource use.
- Store history atomically in device-protected storage, safely migrate legacy history after unlock. Keep seven days. Availability is time-weighted; unobserved gaps are explicit and break outages. Show day/week and export CSV through Android's document picker.
- Battery profiles: economical (60 s awake / 300 s screen off / 60 s failure), balanced (30/120/30), frequent (15/60/10); retain explicit custom intervals. OS may defer alarms.
- Release build signed with a persistent secret key; fail closed without signing configuration. Debug builds never overwrite the public release. Version code increases with CI run number. Preserve versioned release history.
- Unit tests for deadlines, protocol parsing, gaps/time weighting, retention/storage and profiles; Android smoke checks for lifecycle/network/upgrade; lint in CI.

User authorization: “сделай все что предложил” follows the full review and recommendations. Routine implementation decisions are made within that approved scope. A missing signing secret is an operational dependency, not permission to publish an unsigned/debug release.
