# B — Insights and webhook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Показать полноту данных и состояние монитора, добавить JSON и безопасный пример HA.

**Architecture:** Фоновые агрегаты и готовый immutable snapshot питают UI и webhook. Event tracker знает измеренные переходы, delivery gate отвечает только за доставку.

**Tech Stack:** Kotlin/JVM 17, Android Views, Gradle 8.14.6, AGP 8.13.2, Kotlin 2.4.21, JUnit 4; дополнения указаны в задачах.

**Spec:** [Утверждённая спецификация](../specs/2026-10-17-insights-webhook-design.md), [общие ограничения](../specs/2026-10-17-growth-design.md).

## Global Constraints

- Телефон: Android API 26+, compile/target API 35; Kotlin и существующий Views UI.
- Один foreground-сервис; нет сервера автора, учётной записи или фоновой телеметрии.
- Дополнительные цели и внешние интеграции выключены по умолчанию.
- Недоступность измерения, отсутствие сети и измеренный сбой сервиса различаются.
- `claude/project-thread-s0ejzl` не изменяется, не удаляется и не используется как рабочая ветка.
- Существующие API v1, виджет Telegram и чтение бэкапов v1/v2 сохраняются.
- Новые пользовательские строки добавляются на русском и английском.
- Один адрес; прежний `text/plain` остаётся форматом по умолчанию.
- JSON schema_version=1; latency отсутствующего измерения — null, никогда -1.
- Deadline 10 секунд, connect/read по 5 секунд; одинаковый успешный статус не heartbeat.

## Review Focus

- Сутки с 20 минутами данных — uptime и покрытие имеют разные знаменатели (Task 1).
- Старый запрос другого периода не подменяет выбранное окно (Task 1).
- UNKNOWN между DOWN и OK — recovery остаётся корректным (Task 2).
- Смена формата/фильтров в полёте — старый callback не меняет новое поколение (Task 3).
- Событийный webhook молчит при стабильном OK — HA не объявляет бесконечный online (Task 3).

---

План подготовлен для согласования; ни один checkbox не означает уже выполненную реализацию.
Пути ниже относительно корня репозитория; команды выполняются из `/workspace/TgWatch`
после `source /workspace/.tgwatch-setup/env.sh`. Android-команды требуют отдельного
тестового эмулятора/устройства; отсутствие устройства обозначается как unrun.

### Task 1: Покрытие и здоровье одного снимка

**Files:**
- Create: `app/src/main/java/ru/tgwatch/CoverageStats.kt`
- Create: `app/src/main/java/ru/tgwatch/MonitorHealth.kt`
- Modify: `app/src/main/java/ru/tgwatch/HistoryDisplay.kt`
- Modify: `app/src/main/java/ru/tgwatch/MainHistoryUi.kt`
- Modify: `app/src/main/java/ru/tgwatch/MainActivity.kt`
- Modify: `app/src/main/java/ru/tgwatch/MonitorService.kt`
- Modify: `app/src/main/java/ru/tgwatch/DiagnosticSnapshot.kt`
- Modify: `app/src/main/java/ru/tgwatch/LocalDiagnostics.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Test: `app/src/test/java/ru/tgwatch/CoverageStatsTest.kt`
- Test: `app/src/androidTest/java/ru/tgwatch/MonitorHealthUiTest.kt`

**Interfaces:**
- Consumes: HistoryRead, ProbeAdmissionSnapshot и RecoveryState из A; ScheduleDiagnostics.Snapshot.
- Produces: `CoverageStats(assessedMs: Long, offlineMs: Long, unknownMs: Long, windowMs: Long, percent: Double?)`; `CoverageStats.from(stats: TimeStats, windowMs: Long): CoverageStats`, пустое покрытие даёт null percent.
- Produces: `MonitorHealth` с checkStartedElapsed, notMeasuredCount, primaryBusy, secondaryBusy, storageFailure, recoveryState; без адресов/токенов.
- Produces: HistoryDisplay содержит uptime и coverage за одно и то же выбранное окно/epoch/revision; MainHistoryUi применяет latest-only результат.

- [ ] **Step 1: Написать регрессионные тесты.** `twentyMinutesOfOkIsNotFullDayCoverage`, `noDataIsNotZeroUptime`, `rollbackDoesNotDoubleCountCoverage`, `storageFailureIsNotOutage`, `olderWindowCannotReplaceCurrent`: окно ровно 86 400 000 ms, OK 1 200 000 ms.

```kotlin
assertEquals(100.0, uptime, 0.001)
assertEquals(1_200_000L, coverage.assessedMs)
assertEquals(86_400_000L, coverage.windowMs)
assertEquals(100.0 / 72.0, coverage.percent!!, 0.001)
assertNull(emptyCoverage.percent)
assertFalse(networkOutageShownForStorageFailure)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.CoverageStatsTest --tests ru.tgwatch.ClockEpochBoundaryTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Считать coverage по union сегментов текущего epoch из Timeline, не по числу проверок. Исправить HistoryDisplay, где uptime сейчас всегда за сутки даже при выбранной неделе. Показать текст покрытия и отдельный блок здоровья, не приписывать задержки Doze. Локальный export использует allowlist и typed storage failures. Выполнить bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.MonitorHealthUiTest,ru.tgwatch.HistoryFailureUiTest --no-daemon.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.CoverageStatsTest --tests ru.tgwatch.ClockEpochBoundaryTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/CoverageStats.kt app/src/main/java/ru/tgwatch/MonitorHealth.kt app/src/main/java/ru/tgwatch/HistoryDisplay.kt app/src/main/java/ru/tgwatch/MainHistoryUi.kt app/src/main/java/ru/tgwatch/MainActivity.kt app/src/main/java/ru/tgwatch/MonitorService.kt app/src/main/java/ru/tgwatch/DiagnosticSnapshot.kt app/src/main/java/ru/tgwatch/LocalDiagnostics.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml app/src/test/java/ru/tgwatch/CoverageStatsTest.kt app/src/androidTest/java/ru/tgwatch/MonitorHealthUiTest.kt` и `git commit -m "feat: show observation coverage and monitor health"`.

### Task 2: Контракт событий и JSON без новых чтений истории

**Files:**
- Create: `app/src/main/java/ru/tgwatch/WebhookEvent.kt`
- Create: `app/src/main/java/ru/tgwatch/WebhookPayload.kt`
- Modify: `app/src/main/java/ru/tgwatch/WebhookPolicy.kt`
- Modify: `app/src/main/java/ru/tgwatch/WebhookNotifier.kt`
- Modify: `app/src/main/java/ru/tgwatch/WebhookDelivery.kt`
- Modify: `app/src/main/java/ru/tgwatch/MonitorService.kt`
- Test: `app/src/test/java/ru/tgwatch/WebhookEventTest.kt`
- Test: `app/src/test/java/ru/tgwatch/WebhookPayloadTest.kt`
- Modify: `app/src/test/java/ru/tgwatch/WebhookDeliveryBehaviorTest.kt`

**Interfaces:**
- Consumes: проверенная публикация A и готовые сетевые поля, без History.stats.
- Produces: `WebhookObservation(serviceId: String, status: String, checkedAtMs: Long, latencyMs: Long?, network: NetworkKind, revision: Long, measured: Boolean, stale: Boolean)`.
- Produces: `NetworkKind { WIFI, CELLULAR, VPN, ETHERNET, OTHER, UNKNOWN }`; `WebhookEventTracker.observe(observation: WebhookObservation): WebhookEvent?`, с last observed отдельно от last delivered.
- Produces: `WebhookEvent(kind: WebhookEventKind, observation: WebhookObservation?, previousStatus: String?)`; observation=null только TEST.
- Produces: `WebhookFormat { TEXT, JSON }`, `WebhookEventKind { UNREACHABLE, PARTIAL, NETWORK_LOST, RECOVERY, NETWORK_RESTORED, TEST }`.
- Produces: `WebhookBody(contentType: String, bytes: ByteArray)`; `WebhookPayload.encode(event: WebhookEvent, format: WebhookFormat): WebhookBody` и transport `submit(url: String, body: WebhookBody, agent: String, finished: (Int) -> Unit): Boolean`.
- TEST имеет event=test, status/checked_at_ms/latency_ms=null и не проходит tracker измерений; остальные поля по schema v1.

- [ ] **Step 1: Написать регрессионные тесты.** `initialOkIsSilent`, `recoverySurvivesUnknown`, `networkRestoredIsNotOutageRecovery`, `jsonUsesNullableLatencyAndAllowlist`, `noPublishForUnmeasuredOrStale`: JSON проверять через независимый org.json parser; fake HTTP connection фиксирует байты/headers.

```kotlin
assertNull(initialHealthyEvent)
assertEquals(WebhookEventKind.RECOVERY, afterUnknown.kind)
assertEquals(WebhookEventKind.NETWORK_RESTORED, afterOffline.kind)
assertEquals("DOWN", json.getString("status"))
assertTrue(json.isNull("latency_ms"))
assertEquals("application/json; charset=utf-8", body.contentType)
assertEquals(allowedKeys, json.keys().asSequence().toSet())
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.WebhookEventTest --tests ru.tgwatch.WebhookPayloadTest --tests ru.tgwatch.WebhookDeliveryBehaviorTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Захватывать immutable observation в MonitorService при подтверждённой публикации; TG_DOWN преобразуется в DOWN только JSON. Отделить last observed от retry/last delivered; UNKNOWN не сбрасывает причину. ByteArray копируется в request, не логируется. Сохранить text/plain по умолчанию, 5/5-second timeouts, отсутствие redirects и один physical delivery slot.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.WebhookEventTest --tests ru.tgwatch.WebhookPayloadTest --tests ru.tgwatch.WebhookDeliveryBehaviorTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/WebhookEvent.kt app/src/main/java/ru/tgwatch/WebhookPayload.kt app/src/main/java/ru/tgwatch/WebhookPolicy.kt app/src/main/java/ru/tgwatch/WebhookNotifier.kt app/src/main/java/ru/tgwatch/WebhookDelivery.kt app/src/main/java/ru/tgwatch/MonitorService.kt app/src/test/java/ru/tgwatch/WebhookEventTest.kt app/src/test/java/ru/tgwatch/WebhookPayloadTest.kt app/src/test/java/ru/tgwatch/WebhookDeliveryBehaviorTest.kt` и `git commit -m "feat: add versioned privacy-preserving JSON webhook events"`.

### Task 3: Приватные настройки формата, фильтров и пример HA

**Files:**
- Modify: `app/src/main/java/ru/tgwatch/WebhookSettings.kt`
- Modify: `app/src/main/java/ru/tgwatch/WebhookPolicy.kt`
- Modify: `app/src/main/java/ru/tgwatch/WebhookNotifier.kt`
- Modify: `app/src/main/java/ru/tgwatch/AutomationSettingsActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `docs/automation.md`
- Create: `docs/examples/home-assistant/tgwatch-events.yaml`
- Create: `scripts/tests/test_ha_example.py`
- Modify: `app/src/test/java/ru/tgwatch/WebhookCoordinatorTest.kt`
- Modify: `app/src/androidTest/java/ru/tgwatch/WebhookSettingsIntegrationTest.kt`

**Interfaces:**
- Consumes: WebhookEventKind/WebhookFormat/WebhookBody из Task 2.
- Produces: `WebhookConfiguration` дополнен format и events: Set<WebhookEventKind>; default TEXT и пять рабочих событий, TEST не пользовательский фильтр.
- Produces: `WebhookSettings.setDeliveryResult(ctx: Context, format: WebhookFormat, events: Set<WebhookEventKind>): WriteResult`; commit всей конфигурации под одним monitor с новым generation.
- Produces: HA YAML example, сохраняющий generic status и checked_at_ms как последнее событие, без бесконечного online sensor.

- [ ] **Step 1: Написать регрессионные тесты.** `formatChangeRejectsOldCallback`, `filteredEventStillUpdatesMeasuredBaseline`, `failedSaveInvalidatesInMemoryGeneration`, `testDoesNotChangeBaseline`: удержать physical request при смене формата/фильтров. Python fixture проверяет HA template mappings, разрешённые поля и наличие stale caveat.

```kotlin
assertEquals(1, physicalRequestsWhileOldRequestBlocked)
assertFalse(oldCallbackUpdatesNewHealth)
assertEquals(WebhookEventKind.RECOVERY, recoveryAfterFilteredDown.kind)
assertEquals(WebhookFormat.TEXT, defaultConfiguration.format)
assertFalse(restoredBackupContainsWebhookSettings)
```

- [ ] **Step 2: Подтвердить красный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.WebhookCoordinatorTest --tests ru.tgwatch.WebhookEventTest --no-daemon`. Ожидается ошибка нового контракта либо указанное поведенческое расхождение; сбой окружения сначала диагностировать.
- [ ] **Step 3: Реализовать контракт.** Добавить UI format/filters в credential settings; не включать их в backup/Direct Boot. Фильтр не меняет измеренную baseline; нельзя накопить очередь старых событий. Test send явный и поколение-изолированный. В HA использовать testable template payload, секретный webhook_id, label «Последнее событие»; текстовый ntfy не называть generic JSON API. Выполнить bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tgwatch.WebhookSettingsIntegrationTest --no-daemon и `python3 -m unittest discover -s scripts/tests -p test_ha_example.py`.
- [ ] **Step 4: Подтвердить зелёный тест.** `bash ./gradlew :app:testDebugUnitTest --tests ru.tgwatch.WebhookCoordinatorTest --tests ru.tgwatch.WebhookEventTest --no-daemon`. Все выбранные тесты исполнены, 0 failures/errors; skipped отдельно.
- [ ] **Step 5: Зафиксировать проверенную задачу.** `git add -- app/src/main/java/ru/tgwatch/WebhookSettings.kt app/src/main/java/ru/tgwatch/WebhookPolicy.kt app/src/main/java/ru/tgwatch/WebhookNotifier.kt app/src/main/java/ru/tgwatch/AutomationSettingsActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml docs/automation.md docs/examples/home-assistant/tgwatch-events.yaml scripts/tests/test_ha_example.py app/src/test/java/ru/tgwatch/WebhookCoordinatorTest.kt app/src/androidTest/java/ru/tgwatch/WebhookSettingsIntegrationTest.kt` и `git commit -m "feat: configure webhook event filters and document Home Assistant"`.

## Проверка этапа

Выполнить `bash ./gradlew detekt testDebugUnitTest lintDebug assembleDebug --no-daemon`.
Android: MonitorHealthUiTest, HistoryFailureUiTest, WebhookSettingsIntegrationTest на API29/35.
Выполнить `python3 -m unittest discover -s scripts/tests -p test_ha_example.py`.
Сопоставить документацию JSON с golden fixture, отдельно проверить отсутствие credentials
в backup, diagnostics, SavedState и Direct Boot. Реальный HA instance, если недоступен,
не объявлять проверенным только по fixture/YAML.


После прохождения проверок записать их фактические результаты в описание PR.
Новые версии, теги и ассеты не публиковать из тестовых шагов. Интеграция и выпуск
проходят существующие проверки подписи, upgrade и source/APK/asset integrity.
