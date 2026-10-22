# TgWatch для строки меню Mac

Нативное приложение для macOS 13+ проверяет публичные страницы YouTube, Gemini, Notebook,
Antigravity и ChatGPT / dots на подключении самого Mac. Ответ сайта не подтверждает работу аккаунта,
видео или ИИ-функций. Страна Cloudflare — контекст, а не доказательство маршрута к каждому сервису.

## Сборка и запуск

Нужны Apple Command Line Tools / Xcode и установленный Python 3.10+ (рекомендуется актуальная
поддерживаемая версия). Приложение не устанавливает их автоматически. Если Python не найден,
оно объяснит причину и предложит открыть официальный сайт Python.

1. В скачанном исходном репозитории откройте двойным щелчком `tools/Build-TgWatch-Mac.command`.
2. Finder покажет `build/macos/TgWatch-mac-local-build.zip`. Распакуйте архив в обычной локальной папке
   (вне синхронизируемых папок), переместите TgWatch.app в «Программы» и откройте его.
3. Нажмите значок TgWatch в строке меню, выберите сервисы и «Начать мониторинг».

Приложение запоминает явно включённый мониторинг для следующего запуска.
Окно Terminal не нужно оставлять открытым для работы приложения. «Проверить сейчас» начинает
свежую серию проверок; остановка и смена профилей отбрасывают результаты старого процесса.
Mac должен оставаться включённым. Сон и настройки сети могут задержать проверки.

Сборка универсальная: Apple Silicon и Intel, минимальная версия macOS13. Подпись **ad-hoc для локальной
сборки**, без Apple Developer ID и нотариализации. Сборка не является нотариализованным установщиком.
Защиту Gatekeeper отключать не требуется; используйте доверенную локальную сборку исходного кода.
CI сохраняет такую же локальную сборку как артефакт вместе с этой инструкцией.

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

## Проверки для разработчиков

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*regional*.py' -v
python3 -m unittest discover -s scripts/tests -p test_mac_app_ipc.py -v
bash scripts/test-mac-app.sh
```

Тесты компилируют Foundation-модель, запускают настоящий Python-процесс, проверяют остановку,
собирают универсальный `.app`, проверяют plist, ресурсы и ad-hoc-подпись и создают нативное меню
без включения мониторинга, запросов разрешений или регистрации автозапуска.
Проверки разрешений уведомлений и подтверждения Login Items требуют отдельного интерактивного сеанса.

## English

The macOS13+ menu-bar app runs independent public-site checks on this Mac. Install compatible Python3.10+
and Apple Command Line Tools to build, then double-click `tools/Build-TgWatch-Mac.command`.
Extract `build/macos/TgWatch-mac-local-build.zip` outside synchronized folders, move TgWatch.app to
Applications, open it and explicitly start monitoring. The app remembers your explicit start/stop choice.
Notifications and launch at login are separate opt-ins; macOS may require approval. No Terminal window
is needed while using the app. Python is required at runtime and is not bundled or installed automatically.
The universal arm64/x86_64 build is locally ad-hoc signed, **not Developer ID signed or notarized**.
Never disable Gatekeeper. Public responses do not prove account or AI-feature access. Focus/sleep can delay
checks or notification display. The existing CLI `--json` schema is unchanged; `--app-json` is private IPC.
