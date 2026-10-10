# Progress — plan: 2026-10-10-portability-and-release.md

Baseline:4d33b2621153e3c35ece9d4c27757d90736af828. Baseline command testDebugUnitTest lintDebug assembleDebug exited0;51 tests,0 failures/errors/skips.
Previous1.2 working tree preserved by tar --compare, refs/stash and verified full bundle. Branch codex/tgwatch-1.10.

Preflight interfaces/ownership:
| Tasks | Shared surface | Resolution |
|---|---|---|
|2/6|Prefs minute API,service callback|Task2 owns Prefs/widget; parent owns Activity/Service integration.|
|3/4/6|Observation and validated snapshot|Task3 retains data fields; task4 returns typed snapshot; parent applies via task3 adapters.|
|4/5|JVM org.json|Task5 adds test-only dependency; Android uses system implementation.|
|5/6|Version/docs|Task5 owns Gradle/workflow; parent writes documentation after implemented behavior is known.|
|1–5|Gradle/cache/Git index|Only coordinator invokes full Gradle and commits; pure focused harness may run concurrently.|

Scope: implement confirmed proposals from the review and its required persistence/CI safeguards. No full Telegram client, NTP dependency, network-stack rewrite or hardware signing integration.
