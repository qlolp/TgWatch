# Progress — plan: 2026-10-11-next-improvements.md

Started from current main e3d5348 after preserving previous branch history.
User requested autonomous implementation of reviewed recommendations.

| Tasks | Shared boundary | Pre-flight decision |
| --- | --- | --- |
| 1/2 | targetSDK/insets | Gradle owner sets35; UI owner verifies insets |
| 1/3 | automation navigation | new Activity owns UI; MainActivity adds one hook |
| 1/4 | local diagnostic export | exporter owns SAF callback; MainActivity delegates |
| 3/4 | request check | running-only command, serialized with stop/restore |
| 2/5 | versions/docs | release owner changes scripts; root final docs |
| all | manifest/Gradle/git | root manifest/git; build owner Gradle; serialize builds |

Tasks1..6 self-consistency checked against spec; no contradictory ownership.
Design decision: retain native UI, bounded workers and current history storage;
large product changes and external telemetry are deferred because the accepted
review identified a separate scope or recovery/security implications.
