# TgWatch 1.16: reliable publication and private settings

The user requested an independent audit, implementation of useful improvements,
and a new signed release. Existing native UI, API26 minimum, API35 target,
Direct Boot, seven-day history and portable backup compatibility remain required.
The user's `claude/project-thread-s0ejzl` branch must remain unchanged.

## Scope

- A failed write of the diagnostic last-success timestamp must not discard an OK
  result. Restore-time clearing stays strict so another device's status cannot leak.
- An interrupted probe batch must not publish incomplete outcomes. Preserve the
  previous observation and notification; retry after at most ten seconds. Ordinary
  UNKNOWN and actual network changes retain their existing truthful semantics.
- Capture a check's revision before its network snapshot and recheck it under the
  publication lock. Late EXTRA results remain tied to the same published check.
- Compute notification history statistics on the monitor worker. The foreground
  notification uses a nonblocking snapshot until calculation completes. Daily
  summary broadcasts use a bounded worker with `goAsync()` and an independent
  elapsed deadline that finishes each pending broadcast exactly once. Expired work
  and deliveries disabled during history loading cannot post a late notification.
- A timed-out or cancelled webhook that still owns its physical slot must be
  visibly blocked across settings changes. Only actual completion releases it.
- Display a masked automation token; explicit sensitive clipboard copying remains
  available. Distinguish invalid webhook addresses from failed storage writes.
- Admit CHECK_NOW and its durable throttle together. Rejected requests must not
  consume a budget; failed throttle persistence must not start a check.
- Protect backup-password and custom-address dialogs from screenshots, saved view
  state and autofill. Custom HTTPS addresses remain available for private hosts;
  explain accurately that their full value is included in encrypted backups.
- Reduce polling of age-only settings/tile labels; broadcasts remain immediate.

## Release

Version 1.16.0 has deterministic Android code 1016000. Preserve the production
certificate and published tags/assets. Integrate reviewed dependency updates with
hash-locked Python tooling; major Gradle/AGP migration is a separate task.

Publication must validate a clean source checkout against the workflow SHA, the
exact signed APK, checksum, production certificate, manifest and audit evidence
before creating a release. Re-download all six assets and compare their bytes.
Failure after creation must be reported without overwriting published evidence.

GET_STATE async/cache changes, orphaning blocked webhooks, framework migrations
and strict Gradle artifact verification are outside this bounded release. The
audit's claim about paths in the published dependency inventory was incorrect.

## Evidence

Meaningful regressions must fail on the prior behavior before fixes. Run the full
JVM/tooling suites, lint, detekt, assembly and dependency scans. Exact PR CI must
pass Android29/35 smoke checks. Release CI must test signed updates from immutable
1.10.47 and the last stable predecessor, then validate the published artifacts.
