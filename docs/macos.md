# TgWatch для строки меню Mac

Нативное приложение для macOS 13+ проверяет публичные страницы YouTube, Gemini, Notebook,
Antigravity и ChatGPT / dots на подключении самого Mac. Ответ сайта не подтверждает работу аккаунта,
видео или ИИ-функций. Страна Cloudflare — контекст, а не доказательство маршрута к каждому сервису.

## Сборка и запуск

Для сборки нужны Apple Command Line Tools / Xcode, сеть и `curl`. Python для запуска не нужен:
builder загружает два pinned runtime Python 3.11.18 от python-build-standalone, проверяет их SHA-256
и включает arm64 и x86_64 варианты в приложение. URL и контрольные суммы зафиксированы в
`scripts/build-mac-app.sh`. Приложение выбирает runtime для своей запущенной архитектуры; это работает
и при запуске Intel-среза через Rosetta.

1. В скачанном исходном репозитории откройте двойным щелчком `tools/Build-TgWatch-Mac.command`.
2. Finder покажет `build/macos/TgWatch-mac-local-build.zip`. Распакуйте архив в обычной локальной папке
   (вне синхронизируемых папок), переместите TgWatch.app в «Программы» и откройте его.
3. Нажмите значок TgWatch в строке меню, выберите сервисы и «Начать мониторинг».

Приложение запоминает явно включённый мониторинг для следующего запуска.
Окно Terminal не нужно оставлять открытым для работы приложения. «Проверить сейчас» начинает
свежую серию проверок; остановка и смена профилей отбрасывают результаты старого процесса.
Mac должен оставаться включённым. Сон и настройки сети могут задержать проверки.

Сборка универсальная: Apple Silicon и Intel, минимальная версия macOS13. По умолчанию это локальная
ad-hoc-подпись без Developer ID и без нотариализации; она не является распространяемой доверенной сборкой.
Не отключайте Gatekeeper и не называйте такую сборку нотариализованной.

Для Developer ID signing установите соответствующий сертификат и private key в доступный `codesign`
Keychain, затем явно задайте `TGWATCH_MAC_SIGN_IDENTITY`. Сборка использует hardened runtime и timestamp.
Для автоматической нотариализации дополнительно задайте все три переменные:
`TGWATCH_MAC_NOTARY_KEY_FILE` (путь к внешнему Apple `.p8` API key), `TGWATCH_MAC_NOTARY_KEY_ID` и
`TGWATCH_MAC_NOTARY_ISSUER`. Файл ключа должен оставаться вне репозитория и логов. Builder отправляет
ZIP через `notarytool`, требует фактический статус `Accepted`, stapling и успешную локальную проверку
Gatekeeper; при ошибке сборка завершается без заявления об успешной нотариализации. Developer ID без
полного набора notary credentials означает только «signed, not notarized». Секреты передавайте через
защищённое окружение CI/Keychain, не через аргументы командной строки или файлы репозитория.

Имена артефактов отражают результат: `TgWatch-mac-local-build.zip` для локальной ad-hoc-сборки,
`TgWatch-mac-developer-id-build.zip` для подписи без нотариализации и
`TgWatch-mac-notarized-build.zip` только после принятого запроса, stapling и assessment.
CI без Apple credentials сохраняет только честно обозначенную локальную сборку.

## Настройки

Мониторинг по умолчанию выключен. Уведомления и запуск при входе включаются вами отдельно.
Запуск при входе использует системные Login Items; macOS может потребовать подтверждение.
Приложение показывает фактическое системное состояние. Разрешение уведомлений также управляется
в настройках macOS; Focus может задержать показ разрешённого уведомления.

Встроенный движок не получает cookies и данные аккаунта, не читает браузер и не переключает VPN.
В интерфейс передаются фиксированные статусы, HTTP-код, страна, время и случайный контекст соединения.
Исходный IP и отпечатки маршрута/выхода остаются только в памяти движка. Изменение контекста,
остановка и замена процесса сбрасывают текущие свидетельства. Старые или повреждённые сообщения
не становятся текущими результатами. «Выйти» дожидается остановки принадлежащих приложению проверок.

## Одноразовое ожидание

После подтверждённого регионального отказа в подменю сервиса можно запросить одно уведомление
о двух свежих публичных ответах с интервалом от 15 секунд. Срок — до 24 часов; функции аккаунта
остаются непроверенными. Вход, CAPTCHA, ошибки и ограничение запросов не завершают ожидание.

Запрос можно отменить; отключение профиля отменяет его. Перезапуск, остановка и смена сети сбрасывают
положительные свидетельства. Сам запрос сохраняется локально до срока окончания, без старой истории
успешных проверок. Запрет уведомлений не расходует запрос. Системный NWPathMonitor сразу закрывает
старым кадрам доступ к меню и тревогам, пока очередь интерфейса обрабатывает изменение пути.
Отпечатки маршрута/IP не записываются в настройки.

## Проверки для разработчиков

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*regional*.py' -v
python3 -m unittest discover -s scripts/tests -p test_mac_app_ipc.py -v
bash scripts/test-mac-app.sh
```

Тесты компилируют Foundation-модель, запускают настоящий Python-процесс, проверяют остановку,
собирают универсальный `.app`, проверяют оба Python runtime и их архитектуры, запускают IPC regression
tests встроенным Python, проверяют plist, ресурсы и ad-hoc-подпись и создают нативное меню без включения
мониторинга, запросов разрешений или регистрации автозапуска.
Проверки разрешений уведомлений и подтверждения Login Items требуют отдельного интерактивного сеанса.

## English

The macOS13+ menu-bar app runs independent public-site checks on this Mac. Apple Command Line Tools/Xcode,
network access and `curl` are needed to build; Python is bundled. The builder downloads pinned Python 3.11.18
arm64 and x86_64 runtimes and verifies both SHA-256 checksums before packaging. The app selects the runtime
for its active architecture (including an Intel/Rosetta launch). Double-click `tools/Build-TgWatch-Mac.command`.
Extract `build/macos/TgWatch-mac-local-build.zip` outside synchronized folders, move TgWatch.app to
Applications, open it and explicitly start monitoring. The app remembers your explicit start/stop choice.
Notifications and launch at login are separate opt-ins; macOS may require approval. No Terminal window
is needed while using the app. By default the universal arm64/x86_64 app is locally ad-hoc signed, **not
Developer ID signed or notarized**. To sign, install a Developer ID certificate in the Keychain and explicitly
set `TGWATCH_MAC_SIGN_IDENTITY`. To notarize, also set `TGWATCH_MAC_NOTARY_KEY_FILE` (external `.p8` key),
`TGWATCH_MAC_NOTARY_KEY_ID`, and `TGWATCH_MAC_NOTARY_ISSUER`. The builder only staples after notarytool reports
`Accepted` and Gatekeeper assessment passes; a Developer ID build without these credentials remains
notarization-unverified. Never disable Gatekeeper. Public responses do not prove account or AI-feature access.
Focus/sleep can delay checks or notification display. The existing CLI `--json` schema is unchanged;
`--app-json` is private IPC.
