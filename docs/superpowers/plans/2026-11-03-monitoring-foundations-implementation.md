# Monitoring Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Track steps with checkboxes.

**Goal:** Выпустить первый проверяемый блок фундаментальных улучшений и опубликовать PR.

**Architecture:** Сохранить MonitorSession как владельца lifecycle и freshness. Добавить переносимые ProbeAdmission и NetworkPathHub в TgWatchCore; адаптер Network.framework оставить в приложении. ServiceCatalog владеет корнем данных.

**Tech Stack:** Swift 5.9+, iOS 17+, SwiftUI, async/await, Network, Swift Testing, XCTest UI.

**Spec:** `docs/superpowers/specs/2026-11-03-monitoring-foundations-design.md`.

## Global Constraints

- Сохранить iOS 17, существующие статусы и history schema 3.
- Не обещать фоновый непрерывный мониторинг, не менять VPN и не добавлять сервер.
- Не создавать новый NWPathMonitor для каждой цели; fixture не обращается в сеть.
- Лимит 2 относится к сервисным измерениям, FIFO; слот освобождается только после окончания транспорта.
- Все новые тесты ядра используют Swift Testing. Тесты не зависят от публичной сети.
- Сохранить изменения предыдущей работы; не переписывать git history и не merge PR автоматически.

## Review Focus

- Отмена между выдачей разрешения и началом транспорта — проверяет Task 1.
- Игнорирующий отмену transport удерживает слот до завершения — проверяет Task 1.
- Второе событие той же сети всё равно инвалидирует результаты — проверяет Task 2.
- Замена/restore контроллера получает текущую сеть и тот же root — проверяет Task 2/3 и ревью интеграции.
- Fixture-архив содержит измерения; Release не активирует fixture — проверяет Task 3 и ревью DEBUG-ветвей.

### Task 1: FIFO admission для сервисных измерений

**Files:** Create `ios/TgWatchCore/Sources/TgWatchCore/ProbeAdmission.swift`, `ios/TgWatchCore/Tests/TgWatchCoreTests/ProbeAdmissionTests.swift`. Modify `ios/TgWatchIOS/MonitorController.swift`.

**Interfaces:** `public actor ProbeAdmission { public init(limit: Int = 2); public func check(using runner: any ProbeRunner, epoch: UInt64) async throws -> ProbeSnapshot }`; `public struct AdmittedProbeRunner: ProbeRunner` с `init(base: any ProbeRunner, admission: ProbeAdmission)`.

- [ ] Написать тесты, удерживающие transport continuations: максимум 2 активных; FIFO для лимита 1; отменённый ожидающий не стартует; слот активного не выдаётся до teardown; throw освобождает слот.
- [ ] Запустить `swift test --package-path ios/TgWatchCore --filter ProbeAdmissionTests` из корня; сначала отсутствующий интерфейс/поведение должно приводить к ошибке, после реализации PASS.
- [ ] Реализовать очередь continuation с UUID, обработкой отмены и освобождением на success/error; не использовать polling/sleep в реализации.
- [ ] Обернуть service runner общим admission, сохранив fixture runner и lifecycle MonitorSession.
- [ ] Запустить весь core suite и BuildProject; отдельный commit.

### Task 2: общий поток сетевых изменений

**Files:** Create `ios/TgWatchCore/Sources/TgWatchCore/NetworkPathHub.swift`, `ios/TgWatchCore/Tests/TgWatchCoreTests/NetworkPathHubTests.swift`, `ios/TgWatchIOS/Features/NetworkPathObserver.swift`. Modify `MonitorController.swift`, `Features/EgressCountryCard.swift`, `Features/VPNComparisonView.swift`.

**Interfaces:** `NetworkPathState: Sendable, Equatable` с `unavailable: Bool`, `kind: String`, `generation: UInt64`; `@MainActor NetworkPathHub` предоставляет `subscribe(_ receive: @escaping (NetworkPathState) -> Void) -> UUID`, `unsubscribe(_ id: UUID)`, `publish(unavailable: Bool, kind: String)`. `@MainActor NetworkPathObserver.shared` предоставляет `hub`.

- [ ] Написать тесты broadcast/replay, повторного одинакового события, unsubscribe и нормализации неизвестного kind.
- [ ] Запустить фильтр NetworkPathHubTests до реализации и зафиксировать RED.
- [ ] Реализовать hub; каждый publish увеличивает generation. Итерировать snapshot списка подписчиков.
- [ ] Перенести NWPathMonitor и классификацию интерфейса в один адаптер; классифицировать NWPath до перехода на MainActor.
- [ ] Подключить сервисы, egress и VPN comparison; weak callbacks, unsubscribe в deinit, fixture не создаёт singleton.
- [ ] Core suite и BuildProject проходят; отдельный commit.

### Task 3: единый root и воспроизводимый backup fixture

**Files:** Create `ios/TgWatchCore/Sources/TgWatchCore/MonitorStorage.swift`, `ios/TgWatchCore/Tests/TgWatchCoreTests/MonitorStorageTests.swift`. Modify `Features/ServiceCatalog.swift`, `MonitorController.swift`, `Features/BackupView.swift`, `TgWatchIOSUITests/MonitorFlowTests.swift`, `docs/ios/README.md`.

**Interfaces:** `MonitorStorage.directory(for definition: ServiceDefinition, root: URL) -> URL`; `MonitorController.init(definition: ServiceDefinition = .telegram, dataRoot: URL, fixtureMode: String? = nil)`; `ServiceCatalog.init(dataRoot: URL? = nil)`.

- [ ] Тестировать расположение Telegram в root и остальных целей в targets/id/revision; две ревизии и два root не пересекаются.
- [ ] Добавить UI fixture сценарий: выполнить проверку → открыть backup → ввести пароль/подтверждение → экспорт; проверить появление share без ошибки. Структуру capture проверить core round-trip тестом с записью истории в тот же root.
- [ ] В DEBUG выбрать один временный root в каталоге, передавать его при init/save/restore. В Release игнорировать fixture arguments; MonitorController не создаёт собственную случайную директорию.
- [ ] Добавить только необходимые accessibilityIdentifier для backup-сценария.
- [ ] Обновить README (schema 3, общий network observer, границы admission, ограничения физической проверки).
- [ ] Запустить полный core suite, BuildProject и доступные UI-проверки; ошибки окружения явно отделить от успеха.

## Дальнейшие выпуски

После этого блока последовательно готовить отдельные спецификации и PR:

1. **Целостность:** migration fixtures legacy/2/3/future; fault injection для full-disk, restore exchange и rollback; явное recovery-состояние. Приёмка: исходные байты сохраняются при каждом недопустимом импорте.
2. **Диагностика:** расширенные transport evidence → объяснение → UI. Приёмка: тестируемая таблица выводов, unknown не превращается в down без подтверждения контроля.
3. **UX:** главное состояние/возраст/следующее действие, единая навигация истории; Dynamic Type/VoiceOver/en/ru. Приёмка: пять сценариев из roadmap проходят на устройстве.
4. **Companion:** общий версионированный модуль, результат refresh вместо старого snapshot, честная датировка summary. Приёмка: одинаковая freshness на всех targets и тесты неизвестной версии.
5. **Экономичность:** baseline на одной/восьми целях, Instruments, только измеренные оптимизации и добровольный redacted diagnostics export. Хранилище менять только по результатам профилирования.

## Публикация

- [ ] Свежий независимый review всего diff от `62eec3e`, исправить существенные замечания с тестами.
- [ ] Сохранить документ с результатами проверок и оставшимися ограничениями.
- [ ] Commit, push `feat/ios-monitoring-foundations`, PR в `main`; проверить GitHub CI и исправить относящиеся к изменениям сбои.
- [ ] Передать пользователю ссылки на подробный план, PR и точный список реализованного блока. Не отмечать весь roadmap выполненным.
