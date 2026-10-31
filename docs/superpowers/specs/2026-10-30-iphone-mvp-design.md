# TgWatch для iPhone: предложение архитектуры и границы первого запуска

Статус: **MVP-дизайн и draft PR утверждены пользователем 2026-10-30; реализация проходит проверку**. Основание: просьба пользователя подготовить iPhone-версию, план и инструменты для тестовых запусков. Ревизия репозитория: `8932ef05a8ffb1af985a7a16e0ae5a8171eda1e1` (v1.27.0), 30 октября 2026. Пользователь подтвердил объём: iPhone 15, iOS 27.2 со слов владельца, без платной Developer Program. Совместимость Xcode/device ещё предстоит проверить.

## Что фактически существует

| Область | Файлы | Повторное использование и пробел |
|---|---|---|
| Android/Wear transport | `probe-core/build.gradle.kts`, `probe-core/src/main/kotlin/ru/tgwatch/probe/{NetworkProbe,ProbeBatch,MtProto}.kt` | JVM17, `java.net`/executors, не Kotlin Multiplatform; бинарно подключить к iOS нельзя. Переиспользовать правила, fixture-векторы и ожидаемые результаты. |
| Строгая проверка MTProto | `probe-core/src/test/kotlin/ru/tgwatch/probe/{MtProtoValidationTest,MtProtoFixtures}.kt` | Источник golden cases: nonce, длины, TL/padding/fingerprints. Перенос тестов обязателен; ответ handshake не подтверждает доставку сообщения или криптографическую аутентификацию сервера. |
| Mac UI | `macos/TgWatch/main.swift`, `RegionalNativeNotifications.swift` | AppKit/NSStatusItem/ServiceManagement, не iPhone UI. Новый SwiftUI target; Mac source не меняется в MVP. |
| Mac engine | `macos/TgWatch/RegionalEngineProcess.swift`, `scripts/regional-services.py` | Запускает bundled Python через `Process`, pipe и POSIX process control. Этот desktop-процесс не используется в iPhone target. Нужен native transport. |
| Mac policies | `macos/TgWatch/RegionalMenuModel.swift`, `MacPublicResponseWatch.swift` и `macos/tests/` | Foundation-модель содержит хорошие правила freshness, generation, network epochs. Это региональные сайты, а не Telegram MTProto. Изучить/портировать семантику; не подключать весь IPC codec как iOS-модель. |
| История/инциденты | `app/src/main/java/ru/tgwatch/{Timeline,ObservationCoverage,IncidentCenter}.kt` и tests | Контракты наблюдений/UNKNOWN как эталон; Android storage/UI заново не использовать. |
| Apple toolchain/CI | `scripts/{build-mac-app,test-mac-app}.sh`, `.github/workflows/mac-app.yml` | Swift компилируется `xcrun swiftc`, тестируется native Mac. Нет `.xcodeproj`, iOS scheme, simulator tests, device signing profile или iOS CI. |

Полный поиск tracked-файлов не обнаружил `ios/`, UIKit/SwiftUI target, Xcode project/workspace или Package.swift. В выбранной cloud-среде Linux; `swift`, `xcodebuild`, `xcrun` отсутствуют. Git/GitHub, Python, JVM/Android SDK доступны. **Состояние MacBook Air не проверено этой ревизией.** Наличие Apple Account, membership, Xcode, доверенного устройства и сертификатов неизвестно.

AGENTS.md и локальные `.agents/skills` в доступном checkout не найдены. Прочитаны cloud-навыки brainstorming и writing-plans; документ не разрешает автоматически выдавать Apple credentials или начинать крупную реализацию до согласования продукта.

## Цель, варианты и рекомендация

Цель: получить устанавливаемое тестовое приложение, которое проверяет доступность Telegram именно с подключения iPhone, показывает основания результата и не выдаёт периоды отсутствия наблюдений за работоспособность.

1. **Рекомендуется: отдельный native iOS target + небольшой Swift package.** SwiftUI UI, Foundation/URLSession HTTP, Network.framework TCP, native local storage. Переиспользуются контракты и fixtures, а не Android runtime и Mac subprocess. Плюс: изолированный путь к Simulator/device; минус: две реализации алгоритмов нужно удерживать общими векторами.
2. **Kotlin Multiplatform ядро.** Потребует выделить commonMain и полностью заменить Java transport/concurrency, изменить build graph и добавить native bindings. Имеет смысл при отдельной цели долгосрочного общего ядра; для первого iPhone теста затрагивает больше проверенного Android/Wear кода. Не выбран без отдельного обоснования и решения пользователя.

PWA/web-wrapper не эквивалентен TgWatch: не покрывает TCP MTProto и системную диагностику. Mac Catalyst не превращает существующий AppKit/Python продукт в iPhone приложение. Эти варианты не используются как обещание более быстрого полноценного порта.

## Предложенный MVP

- Отдельная директория `ios/`; Swift package `ios/TgWatchCore`, приложение/scheme `TgWatchIOS`. Код не импортируется существующими Android/Mac сборками.
- Предварительный minimum iOS **17.0**; изменить до реализации, если тестовый iPhone старше. SDK/Xcode pin фиксируется по реальному Mac и версии iOS устройства, не угадывается из текущей Android CI.
- Только Telegram: native MTProto handshake + существующие web/control endpoints, те же смысловые категории OK/PARTIAL/DOWN/NO_NETWORK/UNKNOWN. Неполный набор primary measurements без положительного Telegram-ответа остаётся UNKNOWN даже при успешном control; admission/cancellation не являются outage. Web-only первый slice маркируется как web-only и не считается готовым Telegram MVP.
- Кнопки «Проверить»/«Отмена», возраст результата, диагностические строки MTProto/web/control, состояние сети. RU/EN ресурсы, Dynamic Type, VoiceOver labels. Не просить Telegram login, телефон, доступ к сообщениям.
- Один активный probe batch. Общий monotonic deadline 6 секунд; максимум 4 одновременно активных соединения. HTTP HEAD с GET fallback только при405/501, без auto-follow redirect; response100…499 подтверждает ответ endpoint, не доступность аккаунта. HTTP body cap64KiB; MTProto response cap 4096 bytes. При отмене/смене сети соединения закрываются, поздний callback не публикуется. Сохранить обычную TLS validation, без ATS exceptions.
- Опциональное foreground-наблюдение каждые 60 секунд, выключено по умолчанию; повторный tick во время запроса пропускается. `scenePhase` inactive/background прекращает loop и отменяет текущий batch. При возвращении — свежая проверка, не backfill пропущенных точек.
- Локальная история 7 дней, максимум 10 000 записей. Новая версия iOS schema1, не обещается Android backup compatibility. При пропуске наблюдений больше 120 секунд интервал UNKNOWN; clock rollback начинает новую epoch. Длительность foreground loop не подменяет полноту наблюдений.
- Stale после 120 секунд отображается как «Нет свежих данных» с последним временем; сохранённый OK не становится текущим при restart. История удаляется только явным действием; ошибки записи показываются, in-memory dirty entries не теряются до retry.
- Нет фоновых уведомлений об outage в первом MVP, APNs, серверов, VPN extension, location/audio обходов, health entitlements, платных библиотек и analytics SDK. Добавление regional sites/своих HTTPS, widgets, полной incident center parity и imports — следующий отдельный этап.

## Фон и смысл наблюдения

iOS не даёт обычному приложению гарантированный бесконечный background timer. BGAppRefresh/BGProcessing — возможности по решению системы, не расписание проверки раз в минуту; force-quit учитывается как остановка пользователем. BGContinuedProcessing предназначен для конкретной пользовательской работы, не является обещанием круглосуточного мониторинга. Это ограничение продукта, не проблема сертификата. См. [Apple DTS: iOS Background Execution Limits](https://developer.apple.com/forums/thread/685525) и [Background Tasks](https://developer.apple.com/documentation/backgroundtasks).

Предложение MVP осознанно не включает фон. Позже можно добавить opportunistic refresh с честными UNKNOWN gaps. Серверная проверка + APNs — отдельный продукт: измеряет сеть сервера, а не локальную сеть/VPN iPhone; пользователь должен принять эту разницу.

## Инструменты и подпись: последовательность без покупки по умолчанию

1. **Read-only inventory на доступном Mac**: `sw_vers`, `xcode-select -p`, `xcodebuild -version`, `xcodebuild -showsdks`, `xcrun simctl list devices available`, `xcrun devicectl list devices`, `df -h`. Если Xcode не активен, сначала остановиться и оформить конкретный install/select шаг; не считать Command Line Tools полным Xcode. Не менять выбранный Xcode, лицензии или runtime в ходе inventory. Проверить подходящий Xcode по [Apple Xcode support](https://developer.apple.com/support/xcode/).
2. **Simulator**: полный Xcode и совместимый iOS Simulator runtime на Mac. Development/distribution certificates и paid membership для simulator milestone не нужны. Сначала сборка и XCTest без device provisioning; факт наличия тестового APK здесь ничего не доказывает.
3. **Собственный iPhone**: пользователь самостоятельно входит Apple Account в Xcode, выбирает свою Team; подключённое/доверенное устройство и Developer Mode по инструкциям Apple. `Apple Development` и development provisioning связаны с bundle identifier/team/device, не с Android ключом. Xcode automatic signing может создавать/регистрировать сущности: такой шаг выполняется отдельно после выбора пользователем аккаунта/команды. Не передавать пароль/2FA в чат.
4. **Personal Team**: Apple документирует бесплатные тесты на своём устройстве с периодическим reprovision; текущая справка указывает 7-дневные profiles. Это путь первого личного теста, не TestFlight. См. [Developer account overview](https://developer.apple.com/help/account/basics/about-your-developer-account).
5. **TestFlight/распространение**: только при необходимости и после проверки уже существующей membership/team. Потребуются Developer Program, App Store Connect и distribution signing/provisioning. Не оформлять enrollment, оплату, договоры или новые API keys автоматически. [Certificates overview](https://developer.apple.com/help/account/certificates/certificates-overview) различает Apple Development/Distribution и **Developer ID для Mac вне Mac App Store**. Получение Developer ID не решает запуск на iPhone.

Не нужны Fastlane, CocoaPods, XcodeGen или новые MCP/плагины для первых локальных тестов. Использовать Xcode, SwiftPM, XCTest и xcrun. Установка full Xcode/runtime — только на выбранном Mac после inventory и конкретного разрешённого шага, не в этом Linux checkout.

## Границы готовности и оценки

| Веха | Результат / gate | Оценка инженерного времени |
|---|---|---|
| A | Mac/toolchain inventory, выбран runtime, package tests и пустой native app запускаются в Simulator | 0.5–1.5 дня после доступа к Mac |
| B | HTTP/MTProto parity fixtures, cancellation/deadline/network generations, честная классификация | 2–4 дня |
| C | UI, foreground lifecycle, ограниченная durable history, RU/EN и accessibility | 2–3 дня |
| D | Simulator integration suite, сборка iPhone, signing пользователем и первый device test | 1–2 дня + ожидание аккаунта/устройства |
| E | TestFlight/полировка/публикация, если потребуется | отдельная оценка после D |

Итого MVP: ориентир **6–11 инженерных дней**, не календарное обещание и не утверждение о выполненной работе; onboarding/toolchain, review Apple и физические проверки могут удлинить срок. Самый ранний демонстрационный запуск — A, он ещё не готовый монитор.

## Вопросы, которые меняют выполнение

- Модель iPhone и версия iOS; какой Mac доступен для Xcode (Air или другой), его macOS и свободное место?
- Первые тесты только на своём iPhone или нужен TestFlight другим людям? Есть ли уже Developer Program/team (без раскрытия credentials)?
- Подходит ли первый объём Telegram + локальная история с foreground-наблюдением, или обязательны региональные сервисы/постоянные фоновые тревоги уже в первом запуске? Последнее требует иной продуктовой границы.

До ответов всё выше остаётся явно предложенным объёмом. Без design review не создавать product scaffold, зависимости или Apple signing assets. План работ сохранён отдельно; пользователь уже запросил сам план, поэтому его подготовка не означает согласование реализации.
