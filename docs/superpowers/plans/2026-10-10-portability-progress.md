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

## Completed implementation and verification

All six plan tasks are complete in the local `codex/tgwatch-1.10` branch. Protocol, settings, history, crypto, release helpers and Android integration passed independent review. Review findings fixed before delivery: CSV preview could restore stale settings/log; pending restore could be re-enabled; rejected service teardown could clip restored history; concurrent export could capture partial application; an existing orphan tag could point to another commit; scan evidence could lack package coverage; maximum clock epochs and latency sums could overflow; widget timestamps could be clipped.

Coordinator verification on 2026-10-10:

- JVM: 132 tests, 0 failures/errors/skips, with actual Android Gradle sources and real JVM org.json.
- Release/signing/SBOM helpers: 47 tests passed with the saved Python virtual environment.
- Android35 emulator: full 17-test integration run passed; final 9-test boundary/widget/queued-service run passed. These cover 20 distinct tests, with six repeated checks.
- Fresh process startup: a staged device-protected journal was replayed by Application.onCreate, deleted after completion, monitoring stayed disabled, settings/log restored and Long.MAX_VALUE epoch rollback persisted without overflow.
- Saved installation script executed successfully; testDebugUnitTest/lintDebug/assembleDebug passed. Final lintDebug/assembleRelease/generateReleaseSbom/selected connectedDebugAndroidTest passed; lint retained 28 warnings and no errors.
- Trivy0.75.0 official binary archive checksum verified. Fresh real scan of both resolved runtime dependencies passed with 0 HIGH/CRITICAL findings. Official GHCR database override was needed because the default mirror was denied.
- Delivery debug APK: ru.tgwatch, versionName1.10, versionCode11, minSdk26,targetSdk34; APK v2 signature verified; SHA-256 file included.
- Environment install/start instructions saved as a configuration draft; publication remains a user action.

The focused UI rerun initially encountered a notification permission dialog after emulator reinstall; the minute-label test now grants/asserts its own notification permission and the rerun passes. A test receiver registration was also corrected for the API26+ overload before final lint.

Evidence and full logs: `/workspace/.tgwatch-tests-1.10/`; download artifacts: `/workspace/artifacts/TgWatch-1.10-*`. No GitHub push/publication or real signing Secrets changes occurred. Local release assembly is unsigned because production signing credentials are absent. Real-phone Doze/OEM battery/locked-boot behavior and the signed CI upgrade gate remain external checks.
