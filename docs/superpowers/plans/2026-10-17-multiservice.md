# C — Multiservice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить четыре независимые цели, их историю, оповещения и безопасный перенос данных.

**Architecture:** TargetRepository отделяет снимки и registry от Android service. Один scheduler использует process admission A; durable generation переключает все target histories и настройки транзакционно.

**Tech Stack:** Kotlin/JVM 17, Android Views, Gradle 8.14.6, AGP 8.13.2, Kotlin 2.4.21, JUnit 4; дополнения указаны в задачах.

**Spec:** [Утверждённая спецификация](../specs/2026-10-17-multiservice-design.md), [общие ограничения](../specs/2026-10-17-growth-design.md).

## Global Constraints

- Телефон: Android API 26+, compile/target API 35; Kotlin и существующий Views UI.
- Один foreground-сервис; нет сервера автора, учётной записи или фоновой телеметрии.
- Дополнительные цели и внешние интеграции выключены по умолчанию.
- Недоступность измерения, отсутствие сети и измеренный сбой сервиса различаются.
- `claude/project-thread-s0ejzl` не изменяется, не удаляется и не используется как рабочая ветка.
- Существующие API v1, виджет Telegram и чтение бэкапов v1/v2 сохраняются.
- Новые пользовательские строки добавляются на русском и английском.
- Одновременно максимум четыре цели: telegram, matrix, whatsapp, custom.
- Telegram: 65 000 строк; каждая новая цель: 11 000; суммарно 98 000; окно семь дней.
- Новые интервалы 60–300 секунд, default 60 секунд; primary/secondary 16/3.
- V3 plaintext 16 MiB/depth 10; v1/v2 8 MiB/65 000; GCM tag проверяется до применения.
- Matrix response 64 KiB; custom HTTPS URL 256 символов, без userinfo/query/fragment.

## Review Focus

- Смена endpoint во время запроса — старая revision не публикуется и не смешивает latency (Tasks 1–2).
- Зависший custom и частые ручные запросы — Telegram публикуется, живая secondary цель не голодает (Task 2).
- Crash/fsync failure между частями миграции — reopen выбирает целую generation (Task 3).
- Максимальный/враждебный v3 JSON — bounded heap, строгие id/tuples/depth (Task 3).
- Locked boot/restore и старый API — custom не стартует, v1 остаётся Telegram (Task 4).

---

План подготовлен для согласования; ни один checkbox не означает уже выполненную реализацию.
Пути ниже относительно корня репозитория; команды выполняются из `/workspace/TgWatch`
после `source /workspace/.tgwatch-setup/env.sh`. Android-команды требуют отдельного
тестового эмулятора/устройства; отсутствие устройства обозначается как unrun.

### Task 1: Определить независимую модель целей и совместимость Telegram

**Files:**
- Create: `app/src/main/java/ru/tgwatch/TargetDefinition.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetSnapshot.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetRepository.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetPreferences.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetHistory.kt`
- Modify: `app/src/main/java/ru/tgwatch/History.kt`
- Modify: `app/src/main/java/ru/tgwatch/Timeline.kt`
- Test: `app/src/test/java/ru/tgwatch/TargetModelTest.kt`
- Test: `app/src/test/java/ru/tgwatch/TelegramCompatibilityTest.kt`

**Interfaces:**
- Consumes: ProbeMeasurement/ProbeAdmission из A, MonitorHealth/CoverageStats из B, существующая Observation.
- Produces: `TargetId { TELEGRAM, MATRIX, WHATSAPP, CUSTOM }` с wire ids telegram/matrix/whatsapp/custom; `TargetStatus { OK, PARTIAL, DOWN, NO_NETWORK, UNKNOWN }`.
- Produces: `TargetAlertSettings(notifyOutage: Boolean, notifyRecovery: Boolean, notifySlow: Boolean, slowThresholdMs: Long)`; `TargetDefinition(id: TargetId, enabled: Boolean, intervalSec: Int, requiresEndpoint: Boolean, alerts: TargetAlertSettings)`.
- Produces: `TargetObservation(row: Observation, endpointRevision: Long)`; legacy rows получают revision=0, новый endpoint отделяет распределение/покрытие от прежнего, не меняя clock epoch.
- Produces: `TargetSnapshot(id: TargetId, status: TargetStatus, checkedAtMs: Long, checkedElapsedMs: Long, expectedIntervalSec: Int, latencyMs: Long?, generation: Long, revision: Long, endpointRevision: Long, health: MonitorHealth)`.
- Produces: `TargetRepository.snapshot(id: TargetId): TargetSnapshot?`, `definitions(): List<TargetDefinition>`; `TargetHistory.readStats(id: TargetId, days: Int, nowMs: Long): HistoryRead<TimeStats>`.
- Produces: TargetPreferences сохраняет публичные флаги отдельно от CP endpoints; notifySlow default Telegram совместим с PR #51, у дополнительных false.

- [ ] **Step 1: Написать регрессионные тесты.** `independentStatesDoNotOverwriteTelegram`, `legacyAdapterRetainsTgDownAndNoLatencySentinel`, `endpointRevisionStartsNewCoverage`, `invalidTargetAndIntervalRejected`: четыре фиксированных id, extra interval 60–300, Telegram прежний пользовательский диапазон.

```kotlin
assertEquals(TargetStatus.OK, repository.snapshot(TargetId.MATRIX)!!.status)
assertEquals("TG_DOWN", legacyTelegram.status.name)
assertEquals(-1L, legacyMissingLatency)
assertEquals(0L, newEndpointCoverage.assessedMs)
assertFalse(defaultMatrix.enabled)
assertFalse(defaultMatrix.alerts.notifySlow)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.TargetModelTest --tests ru.tgwatch.TelegramCompatibilityTest --tests ru.tgwatch.TimelinePortabilityTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Ввести чистые DTO и адаптеры. Внутри новых target histories DOWN канонический, Timeline принимает DOWN и legacy TG_DOWN; v1 API/Telegram-only v2 export возвращают TG_DOWN. История текущего endpointRevision не смешивается с прежней конфигурацией; архив сохраняется и экспортируется как отдельная revision. TargetAlertSettings валидирует порог500..5000 ms кратно100, Telegram-compatible defaults и extras notifySlow=false. Устаревший restored checkedElapsed сбрасывается в 0.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.TargetModelTest --tests ru.tgwatch.TelegramCompatibilityTest --tests ru.tgwatch.TimelinePortabilityTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/TargetDefinition.kt app/src/main/java/ru/tgwatch/TargetSnapshot.kt app/src/main/java/ru/tgwatch/TargetRepository.kt app/src/main/java/ru/tgwatch/TargetPreferences.kt app/src/main/java/ru/tgwatch/TargetHistory.kt app/src/main/java/ru/tgwatch/History.kt app/src/main/java/ru/tgwatch/Timeline.kt app/src/test/java/ru/tgwatch/TargetModelTest.kt app/src/test/java/ru/tgwatch/TelegramCompatibilityTest.kt` и `git commit -m "feat: define independent target state and Telegram compatibility"`.

### Task 2: Запускать цели с общим bounded scheduler

**Files:**
- Create: `app/src/main/java/ru/tgwatch/TargetRunner.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetSchedule.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetEndpoints.kt`
- Modify: `app/src/main/java/ru/tgwatch/MonitorService.kt`
- Modify: `app/src/main/java/ru/tgwatch/NetworkProbe.kt`
- Modify: `app/src/main/java/ru/tgwatch/ExtraProbeCoordinator.kt`
- Modify: `app/src/main/java/ru/tgwatch/CheckAlarm.kt`
- Test: `app/src/test/java/ru/tgwatch/TargetRunnerTest.kt`
- Test: `app/src/test/java/ru/tgwatch/TargetScheduleTest.kt`
- Test: `app/src/androidTest/java/ru/tgwatch/MultiTargetPublicationTest.kt`

**Interfaces:**
- Consumes: TargetDefinition/TargetSnapshot из Task 1; process admission lanes 16/3 из A.
- Produces: `TargetAttempt(id: TargetId, generation: Long, networkRevision: Long, endpointRevision: Long, startedElapsed: Long)`; `TargetReport(status: TargetStatus, outcomes: List<ProbeOutcome>, publishable: Boolean, latencyMs: Long?)`.
- Produces: `TargetRunner.check(attempt: TargetAttempt, definition: TargetDefinition, endpoint: String?): TargetReport`; injected HTTP/socket factories.
- Produces: `TargetSchedule.due(nowElapsed: Long): List<TargetId>`, `request(id: TargetId, nowElapsed: Long): Boolean`, `finished(id: TargetId, nowElapsed: Long): Unit`, `nextWakeElapsed(): Long?`.
- Produces: Repository `publish(attempt: TargetAttempt, report: TargetReport): Boolean` атомарно проверяет generation/network/endpoint revisions.

- [ ] **Step 1: Написать регрессионные тесты.** `matrixRequiresBoundedVersionsJson`, `customRequires2xxAndNoRedirects`, `stuckCustomDoesNotDelayTelegram`, `roundRobinDoesNotStarveLiveTarget`, `lateOldEndpointResultRejected`: Matrix 64 KiB, custom 256 chars, HEAD fallback только 405/501.

```kotlin
assertFalse(matrixWithInvalidJsonIsOk)
assertFalse(redirectWasFollowed)
assertTrue(matrixReadBytes <= 65_536)
assertTrue(telegramPublishedBeforeCustomReleased)
assertTrue(liveTargetWaitMs <= configuredIntervalMs)
assertFalse(repository.publish(oldAttempt, oldReport))
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.TargetRunnerTest --tests ru.tgwatch.TargetScheduleTest --tests ru.tgwatch.ProbeAdmissionTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Telegram остаётся primary, Matrix/WhatsApp/custom/EXTRA делят три secondary permits. Один ближайший alarm, coalesced requests, round-robin. Matrix GET /_matrix/client/versions, parsed nonempty versions; WhatsApp web reachability, custom 2xx. Без успешного control отрицательная проба не становится DOWN. Включённая самостоятельная цель исключает эквивалентную EXTRA. Проверить bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.MultiTargetPublicationTest --no-daemon.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.TargetRunnerTest --tests ru.tgwatch.TargetScheduleTest --tests ru.tgwatch.ProbeAdmissionTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/TargetRunner.kt app/src/main/java/ru/tgwatch/TargetSchedule.kt app/src/main/java/ru/tgwatch/TargetEndpoints.kt app/src/main/java/ru/tgwatch/MonitorService.kt app/src/main/java/ru/tgwatch/NetworkProbe.kt app/src/main/java/ru/tgwatch/ExtraProbeCoordinator.kt app/src/main/java/ru/tgwatch/CheckAlarm.kt app/src/test/java/ru/tgwatch/TargetRunnerTest.kt app/src/test/java/ru/tgwatch/TargetScheduleTest.kt app/src/androidTest/java/ru/tgwatch/MultiTargetPublicationTest.kt` и `git commit -m "feat: schedule independent service probes within shared budgets"`.

### Task 3: Мигрировать журналы и транзакционный бэкап v3

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/ru/tgwatch/TargetStorage.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetMigration.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetBackupCodec.kt`
- Modify: `app/src/main/java/ru/tgwatch/TargetHistory.kt`
- Modify: `app/src/main/java/ru/tgwatch/BackupSnapshot.kt`
- Modify: `app/src/main/java/ru/tgwatch/BackupCodec.kt`
- Modify: `app/src/main/java/ru/tgwatch/PortableBackupCodec.kt`
- Test: `app/src/test/java/ru/tgwatch/PortableBackupCodecTest.kt`
- Modify: `app/src/main/java/ru/tgwatch/BackupManager.kt`
- Modify: `app/src/main/java/ru/tgwatch/BackupUi.kt`
- Modify: `app/src/main/java/ru/tgwatch/RestoreTransaction.kt`
- Test: `app/src/test/java/ru/tgwatch/TargetMigrationTest.kt`
- Test: `app/src/test/java/ru/tgwatch/TargetBackupCodecTest.kt`
- Test: `app/src/androidTest/java/ru/tgwatch/TargetRestoreIntegrationTest.kt`

**Interfaces:**
- Consumes: TargetDefinition/TargetObservation из Task 1, durable worker и restore gate A.
- Produces: `TargetStorage.open(directory: File): TargetStorage`, `read(id: TargetId): List<TargetObservation>`, `replaceGeneration(data: TargetBackupSnapshot): Unit`.
- Produces: `TargetMigration.migrate(legacy: File, destination: File): MigrationResult` с READY/RETRY/FAILED без удаления старого источника до durable completion.
- Produces: `TargetBackupSnapshot(createdAtMs: Long, globalSettings: Map<String, Any>, targets: List<TargetDefinition>, observations: Map<TargetId, List<TargetObservation>>, log: List<String>)`.
- Produces: `TargetBackupCodec.encode(snapshot: TargetBackupSnapshot): ByteArray`, `decode(bytes: ByteArray): TargetBackupSnapshot`; v3 tuple `[targetId, at, until, kind, latencyMs, clockEpoch, endpointRevision]`, ровно 7 элементов.
- Produces: `PortableBackupCodec.encryptTargets(snapshot: TargetBackupSnapshot, password: CharArray): ByteArray`, `decryptTargets(bytes: ByteArray, password: CharArray): TargetBackupSnapshot`.
- Produces: portable TGWB container version3 с прежним header33 bytes/tag16 bytes и новой JSON v3; Telegram-only export сохраняет portable v2. Header/KDF/authentication rules старых контейнеров не меняются: TGWB v1 max4 MiB, TGWB v2 plaintext8 MiB, local TGWBKUP1 plaintext8 MiB. TGWB v3 plaintext16 MiB; общий MAX_ENVELOPE_BYTES<=16 MiB+56, фактический portable overhead49.
- Produces: Gradle Test task `:app:targetBackupHeapTest` использует compiled debug unit test classpath, maxHeapSize=128m/maxParallelForks=1, filter только TargetBackupCodecTest.maximumBackupFits128MiB; dependsOn compileDebugUnitTestSources, не на исполнение всей testDebugUnitTest.
- Container size/version/KDF проверяются до derivation; JSON format/count/depth проверяются только после полной GCM аутентификации. Версии контейнера и JSON различаются.

- [ ] **Step 1: Написать регрессионные тесты.** `crashAtEveryMigrationAndRestoreDurableStep`, `v1v2ImportMapsToTelegram`, `v3RoundTripExcludesCredentials`, `rejectDuplicateIdsBadTupleUnknownKeysAndDepth`, `maximumBackupFits128MiB`: использовать failing fs adapter и crash/reopen loop, heap regression fork JVM 128 MiB.

```kotlin
assertEquals(oldOrNewWholeGeneration, reopenedGeneration)
assertTrue(legacySourceStillExistsUntilCommitted)
assertEquals(98_000, maximumRowsRoundTripped)
assertFalse(decodedBytesContainCredentialUrl)
assertTrue(maximumV3Bytes <= 16 * 1024 * 1024)
assertTrue(v1v2Bytes <= 8 * 1024 * 1024)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.TargetMigrationTest --tests ru.tgwatch.TargetBackupCodecTest --tests ru.tgwatch.BackupCryptoTest --tests ru.tgwatch.RestoreTransactionTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Строить staging generation, fsync каждого файла/каталога, затем atomic manifest switch с pending journal. Telegram 65 000 rows, остальные по 11 000, всего 98 000; parser v3 depth<=10, log150x4096, строгие integer/keys/tuple проверки без JSONObject дерева. TargetDefinition включает latency settings заранее для D. Не увеличивать legacy decode limits. В GCM не отдавать plaintext парсеру до doFinal; v1/v2/local fixtures проверять через PortableBackupCodec, сохранив старые password/length limits. Restore останавливает все цели, требует настройки CP endpoints; legacy payload с URL журналируется только в CP, DP marker содержит transaction id без payload. До unlock такой pending restore остаётся gated; v1/v2 EXTRA URL переносится приватно. Выполнить bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.TargetRestoreIntegrationTest --no-daemon и `bash ./gradlew :app:targetBackupHeapTest --no-daemon`.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.TargetMigrationTest --tests ru.tgwatch.TargetBackupCodecTest --tests ru.tgwatch.BackupCryptoTest --tests ru.tgwatch.RestoreTransactionTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/build.gradle.kts app/src/main/java/ru/tgwatch/TargetStorage.kt app/src/main/java/ru/tgwatch/TargetMigration.kt app/src/main/java/ru/tgwatch/TargetBackupCodec.kt app/src/main/java/ru/tgwatch/TargetHistory.kt app/src/main/java/ru/tgwatch/BackupSnapshot.kt app/src/main/java/ru/tgwatch/BackupCodec.kt app/src/main/java/ru/tgwatch/PortableBackupCodec.kt app/src/test/java/ru/tgwatch/PortableBackupCodecTest.kt app/src/main/java/ru/tgwatch/BackupManager.kt app/src/main/java/ru/tgwatch/BackupUi.kt app/src/main/java/ru/tgwatch/RestoreTransaction.kt app/src/test/java/ru/tgwatch/TargetMigrationTest.kt app/src/test/java/ru/tgwatch/TargetBackupCodecTest.kt app/src/androidTest/java/ru/tgwatch/TargetRestoreIntegrationTest.kt` и `git commit -m "feat: migrate target histories and authenticate bounded v3 backups"`.

### Task 4: Подключить выбор цели, уведомления и API v2

**Files:**
- Modify: `app/src/main/java/ru/tgwatch/DailySummary.kt`
- Modify: `app/src/main/java/ru/tgwatch/DailySummaryReceiver.kt`
- Modify: `app/src/main/java/ru/tgwatch/DailyStatsCache.kt`
- Modify: `app/src/main/java/ru/tgwatch/LastSuccessStore.kt`
- Modify: `app/src/main/java/ru/tgwatch/HistoryDisplay.kt`
- Modify: `app/src/main/java/ru/tgwatch/IncidentDisplay.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetSelectionUi.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetEventNotifications.kt`
- Create: `app/src/main/java/ru/tgwatch/TargetAutomation.kt`
- Modify: `app/src/main/java/ru/tgwatch/MainActivity.kt`
- Modify: `app/src/main/java/ru/tgwatch/MainHistoryUi.kt`
- Modify: `app/src/main/java/ru/tgwatch/IncidentActivity.kt`
- Modify: `app/src/main/java/ru/tgwatch/EventNotifications.kt`
- Modify: `app/src/main/java/ru/tgwatch/AlertActionReceiver.kt`
- Modify: `app/src/main/java/ru/tgwatch/AutomationReceiver.kt`
- Modify: `app/src/main/java/ru/tgwatch/AutomationPolicy.kt`
- Modify: `app/src/main/java/ru/tgwatch/WebhookNotifier.kt`
- Modify: `app/src/main/java/ru/tgwatch/BootReceiver.kt`
- Modify: `app/src/main/java/ru/tgwatch/StatusWidget.kt`
- Modify: `app/src/main/java/ru/tgwatch/StatusTileService.kt`
- Modify: `app/src/main/java/ru/tgwatch/CsvImport.kt`
- Modify: `app/src/main/java/ru/tgwatch/LocalDiagnostics.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Test: `app/src/test/java/ru/tgwatch/TargetAutomationTest.kt`
- Test: `app/src/androidTest/java/ru/tgwatch/TargetUiIntegrationTest.kt`

**Interfaces:**
- Consumes: Repository, runner и storage из Tasks 1–3; immutable WebhookObservation из B.
- Produces: `TargetPreferences.setDefinition(ctx: Context, definition: TargetDefinition): Boolean`, `setEndpoint(ctx: Context, id: TargetId, raw: String): Boolean` с commit/generation и CP-only URL.
- Produces: `TargetAutomation.ACTION_GET_TARGET_STATE`, `ACTION_CHECK_TARGET_NOW` с extra target_id из allowlist, API version 2, теми же token guards и physical admission.
- Produces: `TargetEventNotifications.id(targetId: TargetId, event: String): Int`; PendingIntent request identity включает target/action.
- Produces: CSV v3 с target_id и endpoint_revision; старый CSV import — Telegram. Старый widget/tile/API v1 выбирает Telegram.

- [ ] **Step 1: Написать регрессионные тесты.** `switchingTargetKeepsSeparateHistory`, `oneTargetMuteDoesNotMuteAnother`, `v1StillChecksTelegram`, `v2RejectsUnknownTargetAndBadToken`, `lockedCustomTargetIsNotDown`: только built-in targets допустимы до unlock; открытие настроек показывает публичные дефолты, не URL в diagnostics.

```kotlin
assertEquals(TargetId.TELEGRAM, legacyRequestedTarget)
assertFalse(unauthorizedRequestReturnsState)
assertFalse(customProbeStartedBeforeUnlock)
assertNotEquals(telegramOutageNotificationId, matrixOutageNotificationId)
assertEquals(TargetId.MATRIX, selectedHistoryId)
assertFalse(urlAppearsInDiagnosticExport)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.TargetAutomationTest --tests ru.tgwatch.TelegramCompatibilityTest --tests ru.tgwatch.WebhookCoordinatorTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Добавить target selector, enable/interval/endpoint и independent notification controls. Telegram default/legacy actions неизменны; v1 running=false и CHECK_NOW=STOPPED при выключенной цели Telegram, даже если Matrix поддерживает общий service. Общая ежедневная сводка перечисляет включённые цели без смешения uptime, explicit conversion EXTRA opt-in. Для каждого service_id раздельные webhook baselines/cooldowns, один physical slot. Добавить target-aware CSV, incidents, summary, diagnostics; built-in/cached state доступен до unlock, CP targets parked. Проверить bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.TargetUiIntegrationTest,ru.tgwatch.AutomationIntegrationTest,ru.tgwatch.MinuteQuietHoursAndWidgetTest --no-daemon.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.TargetAutomationTest --tests ru.tgwatch.TelegramCompatibilityTest --tests ru.tgwatch.WebhookCoordinatorTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/DailySummary.kt app/src/main/java/ru/tgwatch/DailySummaryReceiver.kt app/src/main/java/ru/tgwatch/DailyStatsCache.kt app/src/main/java/ru/tgwatch/LastSuccessStore.kt app/src/main/java/ru/tgwatch/HistoryDisplay.kt app/src/main/java/ru/tgwatch/IncidentDisplay.kt app/src/main/java/ru/tgwatch/TargetSelectionUi.kt app/src/main/java/ru/tgwatch/TargetEventNotifications.kt app/src/main/java/ru/tgwatch/TargetAutomation.kt app/src/main/java/ru/tgwatch/MainActivity.kt app/src/main/java/ru/tgwatch/MainHistoryUi.kt app/src/main/java/ru/tgwatch/IncidentActivity.kt app/src/main/java/ru/tgwatch/EventNotifications.kt app/src/main/java/ru/tgwatch/AlertActionReceiver.kt app/src/main/java/ru/tgwatch/AutomationReceiver.kt app/src/main/java/ru/tgwatch/AutomationPolicy.kt app/src/main/java/ru/tgwatch/WebhookNotifier.kt app/src/main/java/ru/tgwatch/BootReceiver.kt app/src/main/java/ru/tgwatch/StatusWidget.kt app/src/main/java/ru/tgwatch/StatusTileService.kt app/src/main/java/ru/tgwatch/CsvImport.kt app/src/main/java/ru/tgwatch/LocalDiagnostics.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml app/src/test/java/ru/tgwatch/TargetAutomationTest.kt app/src/androidTest/java/ru/tgwatch/TargetUiIntegrationTest.kt` и `git commit -m "feat: expose per-target history alerts and authenticated automation"`.

### Task 5: Upgrade и smoke используют manifest поколения

**Files:**
- Modify: `scripts/android-smoke.sh`, `scripts/signed-upgrade-smoke.sh`.
- Modify: `scripts/upgrade-baseline.py`, `scripts/upgrade-policy.py`.
- Modify: `scripts/tests/test_upgrade_baseline.py`, `scripts/tests/test_upgrade_policy.py`.
- Modify: `app/src/androidTest/java/ru/tgwatch/BackupRestoreIntegrationTest.kt`, `app/src/androidTest/java/ru/tgwatch/ClockBoundaryIntegrationTest.kt`.
- Create: `scripts/tests/test_target_upgrade.py`.

**Interfaces:**
- Consumes: authoritative atomic manifest и per-target journal C3; прежний legacy history-v2 источник не изменяется тестом после migration marker.
- Produces: upgrade-baseline canonical target snapshot с id/revision/epochs, без URL; legacy и current rows сравниваются семантически, а не по одному имени файла.
- Produces: smoke checks distinct states NO_NETWORK/recovery/locked public targets; Doze проверка не обещает непрерывные проверки во сне.

- [ ] **Step 1: Написать failing fixtures.** `legacyOnlyUpgradePreservesTelegram`, `manifestSelectsWholeGeneration`, `failedGenerationDoesNotCountAsUpgrade`, `matrixDoesNotSatisfyTelegramAwait`, `offlineObservationMustBeNoNetwork`: fixtures ожидают точную цель/epoch/status; одинаковый timestamp другой цели не даёт false pass.
- [ ] **Step 2: Проверить красный тест.** `python3 -m unittest discover -s scripts/tests -p test_target_upgrade.py`; старые history-v2-only readers не проходят fixtures manifest.
- [ ] **Step 3: Обновить readers и smoke.** Только allowlisted filenames/ids внутри manifest generation, без shell-интерполяции импортированных путей. Старый sentinel/clock data сохраняются; после отключения wifi/data требуется NO_NETWORK текущей цели, после включения — новое измерение. Pending restore и locked CP цель не признаются сбоями сервиса.
- [ ] **Step 4: Выполнить Python suites и Android upgrade smoke.** `python3 -m unittest discover -s scripts/tests`, затем `bash scripts/android-smoke.sh` на disposable API29/35 и штатный production-signed upgrade job. Expected: корректный target, целое поколение, прежние наблюдения и подпись; debug-signature test не подменяет production continuity.
- [ ] **Step 5: Коммит.** Зафиксировать перечисленные реально изменённые scripts/tests; сообщение `test: verify target-aware upgrade and network recovery`.

## Проверка этапа

Выполнить полный JVM/lint/detekt/phone assemble и Android tests API29/35.
Обновить `scripts/android-smoke.sh`, `scripts/signed-upgrade-smoke.sh`,
`scripts/upgrade-baseline.py`, `scripts/upgrade-policy.py` и соответствующие tests:
путь history-v2 больше не является единственным источником истины. Проверить upgrade
с production-подписанной предыдущей версии существующим CI, не подменяя её debug key.
Выполнить `python3 -m unittest discover -s scripts/tests` и отдельные
`scripts/test_dependency_audit.py`, `scripts/test_signing_keychain.py`.
Сохранить fixtures старых backup/CSV/history и доказать их чтение после миграции.


После прохождения проверок записать их фактические результаты в описание PR.
Новые версии, теги и ассеты не публиковать из тестовых шагов. Интеграция и выпуск
проходят существующие проверки подписи, upgrade и source/APK/asset integrity.
