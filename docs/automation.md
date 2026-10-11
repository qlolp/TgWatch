# Automation API (Tasker / Termux / Shortcuts)

TG Monitor exposes an install-time permission and three surfaces for power users.
Nothing is exported without `ru.tgwatch.permission.AUTOMATION`.

## Permission

```
ru.tgwatch.permission.AUTOMATION
```

Normal protection level — granted at install for apps that declare it.

## Broadcasts (commands)

Send an explicit broadcast to package `ru.tgwatch`:

| Action | Effect |
|---|---|
| `ru.tgwatch.action.EXTERNAL_CHECK_NOW` | Enable monitoring and run a check |
| `ru.tgwatch.action.EXTERNAL_START` | Enable and start the foreground service |
| `ru.tgwatch.action.EXTERNAL_STOP` | Disable and stop monitoring |
| `ru.tgwatch.action.EXTERNAL_QUERY` | Emit a `STATE_CHANGED` reply with extras |

## Broadcasts (events)

`ru.tgwatch.action.STATE_CHANGED` (permission-protected) extras:

- `running` (boolean)
- `status` (`OK` / `PARTIAL` / `TG_DOWN` / `NO_NETWORK` / `UNKNOWN`)
- `checked_at`, `since`, `latency_ms`
- `reason`, `uptime_percent`, `stale`, `doze_missed`

## Content provider

```
content://ru.tgwatch.status/current
```

Columns match the event extras (`running`, `status`, …).

## Termux example

```bash
am broadcast -a ru.tgwatch.action.EXTERNAL_CHECK_NOW -p ru.tgwatch
content query --uri content://ru.tgwatch.status/current
```
