# A — Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Устранить ложные свежие сбои, блокировки lifecycle и скрытые ошибки хранения.

**Architecture:** Один process-wide admission отделяет физическую работу от результата. Последовательный history worker и независимый broadcast deadline ограничивают ожидание, не ослабляя durability.

**Tech Stack:** Kotlin/JVM 17, Android Views, Gradle 8.14.6, AGP 8.13.2, Kotlin 2.4.21, JUnit 4; дополнения указаны в задачах.

**Spec:** [Утверждённая спецификация](../specs/2026-10-17-reliability-design.md), [общие ограничения](../specs/2026-10-17-growth-design.md).

## Global Constraints

- Телефон: Android API 26+, compile/target API 35; Kotlin и существующий Views UI.
- Один foreground-сервис; нет сервера автора, учётной записи или фоновой телеметрии.
- Дополнительные цели и внешние интеграции выключены по умолчанию.
- Недоступность измерения, отсутствие сети и измеренный сбой сервиса различаются.
- `claude/project-thread-s0ejzl` не изменяется, не удаляется и не используется как рабочая ветка.
- Существующие API v1, виджет Telegram и чтение бэкапов v1/v2 сохраняются.
- Новые пользовательские строки добавляются на русском и английском.
- Общий бюджет проб — 19: primary 16, secondary 3; вебхук отдельно максимум 1.
- История: логически 65 000 строк/7 дней, физически 66 024 строк/16 MiB.
- Компактизация: 1 024 append или шесть часов; GET_STATE deadline 8 секунд.

## Review Focus

- Пересоздание службы с незавершающимся транспортом — бюджет сохраняется (Task 2).
- Ошибка fsync при заполненной истории — данные не подменяются пустыми (Task 3).
- Stop/start во время record/restore — main свободен, сессии упорядочены (Task 4).
- Одновременные deadline и completion GET_STATE — один finish, без late cache (Task 5).
- Изменение разрешений/quiet/mute вне activity — UI и SLOW согласованы (Task 6).

---

План подготовлен для согласования; ни один checkbox не означает уже выполненную реализацию.
Пути ниже относительно корня репозитория; команды выполняются из `/workspace/TgWatch`
после `source /workspace/.tgwatch-setup/env.sh`. Android-команды требуют отдельного
тестового эмулятора/устройства; отсутствие устройства обозначается как unrun.

### Task 1: Разделить отсутствие измерения и сетевую ошибку

**Files:**
- Modify: `app/src/main/java/ru/tgwatch/NetworkProbe.kt`
- Modify: `app/src/main/java/ru/tgwatch/MonitorService.kt`
- Test: `app/src/test/java/ru/tgwatch/ProbeMeasurementTest.kt`
- Modify: `app/src/test/java/ru/tgwatch/PrimaryNetworkTest.kt`

**Interfaces:**
- Consumes: существующие `ProbeOutcome`, `ProbeReport`, публикация `MonitorService`.
- Produces: `enum ProbeMeasurement { SUCCESS, FAILURE, NOT_MEASURED }`; последний параметр `ProbeOutcome.measurement` по умолчанию выводится из reachable для совместимости.
- Produces: `ProbeReport.publishable: Boolean`; неполный отрицательный набор false, подтверждённый положительный результат true. Недоступные leases/отказ до запуска — NOT_MEASURED.

- [ ] **Step 1: Написать регрессионные тесты.** `busyEndpointsDoNotPublishDown`, `partialAdmissionRetainsPositiveEvidence`, `cancelledGenerationDoesNotAppend`: управляемый транспорт, без настоящего DNS. В первом случае все Telegram endpoints заняты, controls успешны.

```kotlin
assertFalse(busyReport.publishable)
assertTrue(positiveReport.publishable)
assertEquals(previousCheckedAt, publishedState.checkedAt)
assertEquals(previousRows, storedRows)
assertEquals(0, emittedAlerts)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.ProbeMeasurementTest --tests ru.tgwatch.PrimaryNetworkTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Добавить measurement в исходы, сохранить старые конструкторы. Публиковать TG_DOWN только для полного измеренного отрицательного Telegram-набора с успешным control. При publishable=false не менять state/history/alerts, обновить health и запросить повтор через 1 000 ms; revision guard остаётся под общей блокировкой публикации.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.ProbeMeasurementTest --tests ru.tgwatch.PrimaryNetworkTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/NetworkProbe.kt app/src/main/java/ru/tgwatch/MonitorService.kt app/src/test/java/ru/tgwatch/ProbeMeasurementTest.kt app/src/test/java/ru/tgwatch/PrimaryNetworkTest.kt` и `git commit -m "fix: distinguish unavailable probes from measured outages"`.

### Task 2: Сделать допуск проб общим для процесса

**Files:**
- Create: `app/src/main/java/ru/tgwatch/ProbeAdmission.kt`
- Modify: `app/src/main/java/ru/tgwatch/NetworkProbe.kt`
- Modify: `app/src/main/java/ru/tgwatch/MonitorService.kt`
- Test: `app/src/test/java/ru/tgwatch/ProbeAdmissionTest.kt`
- Modify: `app/src/test/java/ru/tgwatch/ProbeInterruptionTest.kt`

**Interfaces:**
- Consumes: ProbeMeasurement из Task 1.
- Produces: `ProbeAdmission(primaryLimit: Int = 16, secondaryLimit: Int = 3)`; `tryAcquire(key: String, lane: ProbeLane): ProbePermit?`, `snapshot(): ProbeAdmissionSnapshot`.
- Produces: `ProbeLane.PRIMARY/SECONDARY`; snapshot primary/secondary counts. `ProbePermit.start(): Boolean`, `cancelBeforeStart(): Unit`, `finished(): Unit` освобождают один permit ровно один раз.
- Produces: `ProbeAdmission.process` singleton; ProbeBatch принимает injected admission и lane, default — process.

- [ ] **Step 1: Написать регрессионные тесты.** `fivePoolRestartsRespectPhysicalBudget`, `cancelBeforeRunReleasesOnce`, `lateCompletionCannotReleaseAnotherPermit`, `sameEndpointIsNotDuplicated`: удерживать операции latch, игнорирующие interrupt, и обязательно отпустить их в finally.

```kotlin
assertTrue(maxPrimaryPhysical <= 16)
assertTrue(maxSecondaryPhysical <= 3)
assertTrue(maxTotalPhysical <= 19)
assertEquals(1, callsForSameEndpoint)
assertEquals(0, admission.snapshot().primary)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.ProbeAdmissionTest --tests ru.tgwatch.ProbeInterruptionTest --tests ru.tgwatch.ProbeMeasurementTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Перенести pool-keyed leases в process-wide registry с ключом group/name/address и независимыми lanes. Новый executor не обнуляет permits; Future timeout не вызывает finished. Rejected execution вызывает только cancelBeforeStart. Сохранить executor caps 16/3 и отдельный delivery slot вебхука.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.ProbeAdmissionTest --tests ru.tgwatch.ProbeInterruptionTest --tests ru.tgwatch.ProbeMeasurementTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/ProbeAdmission.kt app/src/main/java/ru/tgwatch/NetworkProbe.kt app/src/main/java/ru/tgwatch/MonitorService.kt app/src/test/java/ru/tgwatch/ProbeAdmissionTest.kt app/src/test/java/ru/tgwatch/ProbeInterruptionTest.kt` и `git commit -m "fix: bound physical probe work across service restarts"`.

### Task 3: Разделить retention и долг сохранения истории

**Files:**
- Create: `app/src/main/java/ru/tgwatch/HistoryCompaction.kt`
- Create: `app/src/main/java/ru/tgwatch/HistoryRead.kt`
- Modify: `app/src/main/java/ru/tgwatch/History.kt`
- Modify: `app/src/main/java/ru/tgwatch/HistoryStorage.kt`
- Modify: `app/src/main/java/ru/tgwatch/HistoryDisplay.kt`
- Modify: `app/src/main/java/ru/tgwatch/IncidentDisplay.kt`
- Modify: `app/src/main/java/ru/tgwatch/MainHistoryUi.kt`
- Test: `app/src/test/java/ru/tgwatch/HistoryCompactionTest.kt`
- Test: `app/src/androidTest/java/ru/tgwatch/HistoryFailureUiTest.kt`

**Interfaces:**
- Consumes: Timeline/HistoryStorage и прежняя append fsync policy.
- Produces: `HistoryCompaction.onPruned(): Unit`, `needsReplacement(nowMs: Long, diskRows: Int, diskBytes: Long): Boolean`, `savedReplacement(nowMs: Long): Unit`, `savedAppend(): Unit`, `failedWrite(): Unit`.
- Produces: `HistoryRead<T>` = `Ready(value: T)` или `Unavailable(lastKnown: T?, failure: HistoryFailure)`; failure enum READ/WRITE/REPAIR/BACKPRESSURE.
- Produces: `History.readStats(ctx, days, now): HistoryRead<TimeStats>` и `readIncidents(ctx, days, now): HistoryRead<List<OutageEpisode>>`; старые методы больше не превращают Unavailable в пустые данные.

- [ ] **Step 1: Написать регрессионные тесты.** `steadyRetentionAppendsInsteadOfRewriting`, `repairDebtSurvivesFsyncFailure`, `physicalJournalIsBounded`, `unreadableHistoryIsNotNoOutages`: неделя данных, восемь append; отдельные injections append/rename/directory-fsync failure. UI должен показывать ошибку.

```kotlin
assertEquals(0, replacementsForEightNormalAppends)
assertTrue(replaceAtAppend1024)
assertTrue(replaceAtSixHours)
assertTrue(diskRows <= 66_024)
assertTrue(readResult is HistoryRead.Unavailable)
assertFalse(noOutagesMessageVisible)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.HistoryCompactionTest --tests ru.tgwatch.HistoryDurabilityTest --tests ru.tgwatch.HistoryStorageTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Ввести отдельные retentionDue и repairDebt. Replacement при repair, 1 024 append, 6 часах, row/byte cap 66 024/16 MiB; ошибка не сбрасывает долг. При невозможности bounded save остановить рост файла и показать health. Передать typed read failure через оба UI loaders; worker сохраняет последний успешный снимок с признаком ошибки. Экспорт всегда фильтрует семидневное логическое окно. Дополнительно выполнить bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.HistoryFailureUiTest --no-daemon.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.HistoryCompactionTest --tests ru.tgwatch.HistoryDurabilityTest --tests ru.tgwatch.HistoryStorageTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/HistoryCompaction.kt app/src/main/java/ru/tgwatch/HistoryRead.kt app/src/main/java/ru/tgwatch/History.kt app/src/main/java/ru/tgwatch/HistoryStorage.kt app/src/main/java/ru/tgwatch/HistoryDisplay.kt app/src/main/java/ru/tgwatch/IncidentDisplay.kt app/src/main/java/ru/tgwatch/MainHistoryUi.kt app/src/test/java/ru/tgwatch/HistoryCompactionTest.kt app/src/androidTest/java/ru/tgwatch/HistoryFailureUiTest.kt` и `git commit -m "fix: compact retained history without hiding storage failures"`.

### Task 4: Убрать файловые ожидания из lifecycle

**Files:**
- Create: `app/src/main/java/ru/tgwatch/HistorySessionQueue.kt`
- Create: `app/src/main/java/ru/tgwatch/RecoveryCoordinator.kt`
- Modify: `app/src/main/java/ru/tgwatch/MonitorService.kt`
- Modify: `app/src/main/java/ru/tgwatch/TgWatchApplication.kt`
- Modify: `app/src/main/java/ru/tgwatch/BackupManager.kt`
- Modify: `app/src/main/java/ru/tgwatch/MainActivity.kt`
- Test: `app/src/test/java/ru/tgwatch/HistorySessionQueueTest.kt`
- Test: `app/src/androidTest/java/ru/tgwatch/LifecycleStorageBlockingTest.kt`

**Interfaces:**
- Consumes: typed HistoryRead из Task 3, существующий RestoreTransaction.
- Produces: `HistorySessionQueue.open(): Long`, `submit(session: Long, operation: () -> Unit): Boolean`, `close(session: Long, atMs: Long): CompletableFuture<Unit>`; очередь одна на процесс: максимум64 ожидающих records, один активный lifecycle transition и один coalesced следующий session; submit возвращает false при backpressure, не блокирует caller.
- Produces: `RecoveryState { INITIALIZING, READY, FAILED }`; `RecoveryCoordinator.start(ctx: Context): Unit`, `state: RecoveryState`, `whenReady(action: () -> Unit): Unit`.
- Produces: generation fence, разрешающий запись только текущей сессии; старый close упорядочен до первого record новой.

- [ ] **Step 1: Написать регрессионные тесты.** `blockedHistoryDoesNotBlockDestroy`, `oldCloseCannotClipNewSession`, `recoveryFailureDoesNotStartProbes`, `foregroundStartsBeforeRecoveryFinishes`, `blockedDiskDoesNotGrowHistoryQueue`: удержать I/O latch, вызвать main barrier и stop/start, восстановление прервать между durable шагами.

```kotlin
assertTrue(mainBarrierCompletedWithinOneSecond)
assertFalse(newSessionRowsClipped)
assertEquals(0, probesBeforeRecoveryReady)
assertTrue(foregroundNotificationPresent)
assertTrue(pendingJournalPreservedOnFailure)
assertTrue(pendingHistoryRecords <= 64)
assertFalse(stopTransitionRejectedByFullRecordQueue)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.HistorySessionQueueTest --tests ru.tgwatch.RestoreTransactionTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Сериализовать record/endSession/recovery на history worker; 64 pending records, rejected submit отражается BACKPRESSURE и UNKNOWN-пробелом без выдуманной истории, убрать @Synchronized lifecycle ожидание worker I/O. Захват generation и enqueue короткие, без I/O под service monitor. onDestroy не ждёт future/latch; stopped означает прекращение приёма проб, не fsync. Gate restore действует на boot/widget/tile/API. Дополнительно выполнить bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.LifecycleStorageBlockingTest,ru.tgwatch.BackupRestoreIntegrationTest --no-daemon.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.HistorySessionQueueTest --tests ru.tgwatch.RestoreTransactionTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/HistorySessionQueue.kt app/src/main/java/ru/tgwatch/RecoveryCoordinator.kt app/src/main/java/ru/tgwatch/MonitorService.kt app/src/main/java/ru/tgwatch/TgWatchApplication.kt app/src/main/java/ru/tgwatch/BackupManager.kt app/src/main/java/ru/tgwatch/MainActivity.kt app/src/test/java/ru/tgwatch/HistorySessionQueueTest.kt app/src/androidTest/java/ru/tgwatch/LifecycleStorageBlockingTest.kt` и `git commit -m "fix: keep history recovery and teardown off the main thread"`.

### Task 5: Ограничить время ответа GET_STATE

**Files:**
- Create: `app/src/main/java/ru/tgwatch/AutomationStateDispatcher.kt`
- Modify: `app/src/main/java/ru/tgwatch/AutomationReceiver.kt`
- Modify: `app/src/main/java/ru/tgwatch/AutomationStateCache.kt`
- Test: `app/src/test/java/ru/tgwatch/AutomationStateDispatcherTest.kt`
- Modify: `app/src/androidTest/java/ru/tgwatch/AutomationIntegrationTest.kt`

**Interfaces:**
- Consumes: stateSnapshot, кэш 1 000 ms и compute gap 200 ms.
- Produces: `AutomationStateDispatcher.submit(compute: () -> AutomationStateSnapshot, complete: (Result<AutomationStateSnapshot>) -> Unit, finish: () -> Unit): Boolean` с injected executor/clock/deadline для теста.
- Produces: `health(): AutomationComputeHealth` с физическим busy и истечением логического deadline; нет токена/содержимого snapshot в health.

- [ ] **Step 1: Написать регрессионные тесты.** `hangingComputeFinishesPendingAtEightSeconds`, `deadlineCompletionRaceFinishesOnce`, `lateComputeDoesNotCache`, `busyComputeNeverSpawnsReplacement`: fake scheduler и физически зависший worker; reject/deadline-registration exceptions включены.

```kotlin
assertEquals(1, finishCalls)
assertTrue(finishedBy8_000ms)
assertEquals(1, physicalComputations)
assertNull(cacheAfterLateCompletion)
assertEquals(AutomationReceiver.RESULT_RATE_LIMITED, nextResult)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.AutomationStateDispatcherTest --tests ru.tgwatch.AutomationStateCacheTest --tests ru.tgwatch.AutomationPolicyTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Вынести lifecycle pending result по образцу SummaryDispatcher, без очереди replacement work. Deadline 8 000 ms завершает ответ ровно один раз; физический слот удерживается до выхода compute. Кэшировать по времени завершения только current request. Unauthorized guards остаются до admission. Проверить bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.AutomationIntegrationTest --no-daemon.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.AutomationStateDispatcherTest --tests ru.tgwatch.AutomationStateCacheTest --tests ru.tgwatch.AutomationPolicyTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/AutomationStateDispatcher.kt app/src/main/java/ru/tgwatch/AutomationReceiver.kt app/src/main/java/ru/tgwatch/AutomationStateCache.kt app/src/test/java/ru/tgwatch/AutomationStateDispatcherTest.kt app/src/androidTest/java/ru/tgwatch/AutomationIntegrationTest.kt` и `git commit -m "fix: finish asynchronous automation replies by an independent deadline"`.

### Task 6: Завершить мастер и жизненный цикл SLOW

**Files:**
- Modify: `app/src/main/java/ru/tgwatch/OnboardingActivity.kt`
- Modify: `app/src/main/java/ru/tgwatch/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Create: `app/src/main/java/ru/tgwatch/SlowAlertPresentation.kt`
- Modify: `app/src/main/java/ru/tgwatch/EventNotifications.kt`
- Modify: `app/src/main/java/ru/tgwatch/AlertActionReceiver.kt`
- Modify: `app/src/main/java/ru/tgwatch/MonitorService.kt`
- Create: `app/src/main/java/ru/tgwatch/SlowAlert.kt`
- Test: `app/src/test/java/ru/tgwatch/SlowAlertPresentationTest.kt`
- Modify: `app/src/androidTest/java/ru/tgwatch/OnboardingFlowTest.kt`
- Modify: `app/src/androidTest/java/ru/tgwatch/EventNotificationTest.kt`

**Interfaces:**
- Consumes: PR #51 SlowAlert/ShortcutActivity integration, а не изменение исходной ветки; если уже в main — повторный перенос не нужен.
- Produces: `SlowAlertPresentation.reconcile(active: Boolean, enabled: Boolean, muted: Boolean, quiet: Boolean, running: Boolean, nowElapsed: Long): SlowDisplayAction` = SHOW/CLEAR/NONE; факт замедления отдельно от видимости.
- Produces: всегда доступная кнопка мастера и обновление permission actions при resume/result.

- [ ] **Step 1: Написать регрессионные тесты.** `disableMuteStopRemoveExistingSlowNotice`, `quietEndDoesNotBypassCooldown`, `guideReopensAfterAllPermissionsGranted`, `guideReflectsDeniedAndGrantedPermissions`, `rotationKeepsPageAtFontScaleTwo`: сначала воспроизвести retained notification PR #51.

```kotlin
assertFalse(slowNotificationPresentAfterDisable)
assertFalse(slowNotificationPresentAfterMuteOrStop)
assertTrue(guideButtonVisibleWithAllPermissions)
assertEquals(savedPage, pageAfterRecreate)
assertFalse(grantedPermissionActionEnabled)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.SlowAlertPresentationTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Проверить base PR #51 и перенести commit 334831a без merge/push/изменения claude-ветки; при конфликтах сохранить реализованные A1–A5. Подключить reconcile к prefs change, mute, resume monitoring, stop и проверкам. Порог 1 000/600 ms, streak 3, cooldown 30 минут не менять. Перенести кнопку вне setupCard; API26–32 и окончательный отказ открывают settings. Выполнить bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.OnboardingFlowTest,ru.tgwatch.EventNotificationTest,ru.tgwatch.UiAccessibilityTest --no-daemon.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.SlowAlertPresentationTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/OnboardingActivity.kt app/src/main/java/ru/tgwatch/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml app/src/main/java/ru/tgwatch/SlowAlertPresentation.kt app/src/main/java/ru/tgwatch/EventNotifications.kt app/src/main/java/ru/tgwatch/AlertActionReceiver.kt app/src/main/java/ru/tgwatch/MonitorService.kt app/src/main/java/ru/tgwatch/SlowAlert.kt app/src/test/java/ru/tgwatch/SlowAlertPresentationTest.kt app/src/androidTest/java/ru/tgwatch/OnboardingFlowTest.kt app/src/androidTest/java/ru/tgwatch/EventNotificationTest.kt` и `git commit -m "fix: reconcile setup permissions and slow alert visibility"`.

## Проверка этапа

Выполнить `bash ./gradlew detekt testDebugUnitTest lintDebug assembleDebug --no-daemon`.
На API29/35 выполнить `bash scripts/android-smoke.sh`; результаты текущего Doze-раздела
называть восстановлением после idle, не проверкой непрерывных измерений в Doze.
Проверки native cancellation используют latch transports, а не ненадёжный публичный DNS.
Документировать logical retention и максимум шесть часов до физического удаления старых строк.


После прохождения проверок записать их фактические результаты в описание PR.
Новые версии, теги и ассеты не публиковать из тестовых шагов. Интеграция и выпуск
проходят существующие проверки подписи, upgrade и source/APK/asset integrity.
