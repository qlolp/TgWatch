# Persist selected service settings

Base c47ae6c. User explicitly requested removal of the prior session-only scope limit. No platform/security blocker exists; a bounded local JSON file needs no new permissions.

Persist schema1 selected target(Matrix/WhatsApp), interval(60/120/300) and automatic flag. Defaults Matrix/60/off. Prior versions had no settings file: absence migrates to defaults without a write. Never restore an observation as fresh. Restored automatic checks still require the extra-service screen visible and app active; Telegram controls are independent.

Limit decode to1024bytes; reject malformed/ambiguous JSON, unsupported schema, unknown/missing keys, Telegram target or invalid interval. Preserve unreadable files and show an error; use safe defaults in memory. Persist explicit setting changes atomically; on failure show that settings were not saved. Explicit confirmed reset replaces only service settings, not history/Telegram notification settings.

Verify core defaults/roundtrip/restart, no-file migration, corrupt/oversize/duplicate/future inputs, write failure/previous bytes, reset and inactive restored automatic mode. DEBUG-only per-test UUID storage isolates restart UI fixtures. Independent review, local tests, UI CI and exact finalSHA checks. Wait for current API35 to become terminal before push can supersede its concurrency group; do not blind-rerun. No merge/release/signing/permissions/artifact403 retries.
