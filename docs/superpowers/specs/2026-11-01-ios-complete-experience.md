# Complete iPhone experience

Implement all six approved ideas, locally with no account/server. User has delegated design decisions.

1. VPN comparison: explicit before/after fresh Telegram probes plus egress IP/country. Never toggle VPN; failed or interrupted observations cannot imply improvement. Results only in memory.
2. Diagnosis: explain observed evidence (network absent, Telegram failure with working control, partial reachability, slow success, uncertainty). Freshness gates every diagnosis.
3. Favorites: persist public service IDs, show compact rows on Telegram dashboard, prioritize current problems, retain per-service navigation.
4. Widget: latest iPhone observation, country flag and exact measurement time; stale/unknown must be explicit. App Group shared snapshot contains no IP, endpoint or secrets. Timeline schedules expiry. No continuous-background claim.
5. Apple Watch: companion app displaying favorite snapshots and age via WatchConnectivity; refresh asks active iPhone, otherwise explains connection needed. Existing opted-in iPhone notifications mirror through system Watch notification settings. No independent continuous monitoring claim.
6. Backup: encrypted iOS backup of current service configuration, histories and preferences, including private settings. AES-256-GCM with PBKDF2-HMAC-SHA256 (600000 iterations), fresh salt/nonce, password 12–256 characters, bounded 32 MiB plaintext. Preview before replacement, wrong password/corruption cannot mutate state. Confirmed restore stops monitors, stages validated files and atomically swaps the data directory. iOS format is explicit and distinct from Android TGWB.

Constraints: minimum iOS 17; native SwiftUI/async-await. Project changes exclusively via Xcode tools, never direct pbxproj edits. Personal-team signing may not support App Groups: verify and report actual device limitation without claiming simulator support proves installability. English/Russian UI. Push changes to GitHub. Keep existing VPN configuration unchanged.
