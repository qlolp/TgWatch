# Regional service checks

Approved request: repair the current failing Android scenarios, make the exit-country flag trustworthy, and add optional checks of YouTube, Gemini, Notebook, Antigravity and ChatGPT/dots. A Mac must measure its own connection.

## Product behavior

- All five service profiles are off after installation/restore. A dedicated “Services and region” screen enables each profile independently and offers a manual check and an ordinary browser link. Existing Telegram/Matrix/WhatsApp/custom histories and backup formats remain unchanged.
- Public HTTPS checks run through the captured Android Network, share the existing secondary physical connection admission limit, have a 3-second connect/read timeout and a 32-KiB response limit, and never use account cookies, tokens or passwords. One profile is in flight at a time. Normal interval is 300 seconds; screen-off/battery saving uses 600 seconds. Doze can delay execution; the screen always shows result age.
- Results distinguish WEB_REACHABLE (site only, account functionality unverified), REGION_RESTRICTED (explicit region response), AUTH_REQUIRED, CHALLENGE, RATE_LIMITED, NETWORK_ERROR and UNKNOWN. A 403, 451, country flag or login page alone is never proof of an IP geoblock. YouTube web access does not establish video playback; dots and Antigravity website access do not establish an authenticated session or IDE inference.
- Recognize explicit regional error codes and visible regional error messages, not JavaScript bundles, HTML comments, examples or arbitrary response text. Explanations are fixed localized messages. No raw body, IP address, query, redirect URL or exception message is persisted, shared or logged.
- Regional warnings require two observations at least 15 seconds apart on the same network/configuration; the first schedules a confirmation. Disabled profiles, stale tasks and old networks cannot publish or alert. Warn once per incident, with a 5-minute cooldown, respect global mute, maintenance, quiet hours, DND and Android channel blocking. Network failures do not prove recovery.
- Region checks use the existing foreground service and watchdog, including when no old target is enabled. Stopping Telegram must not stop enabled regional profiles. Restore disables every regional profile and clears its transient state before observers resume.

## Country display

Retain only ISO country/time/network metadata from Cloudflare trace. Reject unknown/non-ISO codes, refresh on active-network changes and manual action, and display country name, source and age. Old-network, future-dated, expired or failed-refresh results are marked unverified/stale. Reject late responses after network switches or a newer request. Never show the phone country as the Mac's country.

## Mac companion

Provide a dependency-free Python CLI with one-shot and foreground watch modes, the same fixed service profiles, conservative result categories and confirmation/cooldown rules. Reports contain safe enums, timestamps and country only; no account credentials or raw responses. Optional macOS notifications target the local user. Do not install an always-on agent or change browser security settings automatically.

An optional user-invoked browser bookmarklet checks visible error text in the user's existing session and displays a local verdict. It does not read/export cookies, storage or page contents, send network requests, or claim that a normal page proves authenticated functionality. This supplies the session-level check public probes cannot perform. Automatic private API calls and account-session scraping are excluded.

## Validation and release

Keep the failing baseline evidence. Update the legacy backup fixture for the new retention setting, pin diagnostics schema 5, and fix the large-font retention controls without weakening assertions. New policy/transport tests must reject generic 403/CAPTCHA/static-bundle false positives and stale network/configuration results. Android integration proves independent opt-ins, same-service lifecycle, restore reset, country freshness and blocked/quiet notifications. Python tests use real local HTTP fixtures or injected transport boundaries and exercise timeouts, redaction and alerts. Run full unit/Python/lint/detekt and API26/29/35/36 CI, fresh whole-branch review, signed phone/Wear publication and real predecessor upgrade with the permanent signing identity.
