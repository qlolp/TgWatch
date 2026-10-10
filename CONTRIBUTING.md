# Разработка TgWatch

TgWatch наблюдает доступность Telegram без аккаунта, телефона и доступа к переписке. Перед изменением поведения прочитайте [README](README.md) и ограничения проверки MTProto.

## Сборка

Нужны JDK 17, Android SDK Platform 35, Build Tools 34.0.0 и принятые лицензии Android SDK. Используйте Gradle Wrapper из репозитория; устанавливать отдельный Gradle не нужно.

Укажите SDK через `ANDROID_HOME` или локальный `local.properties` с `sdk.dir`. Этот файл не коммитится.

```bash
bash ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

`assembleRelease` без signing environment создаёт неподписанный APK для проверки сборки. Пользовательские APK выпускаются через GitHub Actions с постоянным ключом. Debug APK предназначен только для тестов.

## Проверки Android

Instrumentation-тесты проверяют хранилище, настройки и настоящие уведомления. Полный сценарий запускайте только на отдельном root-capable эмуляторе API 29 или 35:

```bash
bash scripts/android-smoke.sh
```

Сценарий устанавливает APK, заменяет тестовые данные, меняет подключение и режим сна, устанавливает PIN и перезагружает эмулятор. Не запускайте его на личном телефоне. Отчёты и скриншоты появляются в `app/build/reports/androidTests/`; журнал — в `smoke-logcat.txt`.

Перед публикацией `scripts/signed-upgrade-smoke.sh` отдельно проверяет обновление настоящего опубликованного APK, настройки, историю и свежие наблюдения. Тестовая миграция со старой 1.6 не восстанавливает её утраченный публичный debug-ключ.

## Как предложить изменение

1. Создайте ветку и опишите проблему, ожидаемое поведение и способ проверки.
2. Для изменения логики добавьте регрессию: статусы, пробелы истории, часы, отмена запросов и фоновые ограничения особенно важны.
3. Запустите unit tests и lint. Изменения Android-службы, уведомлений или экрана должны пройти CI на API 29/35.
4. В PR объясните поведение для пользователя, совместимость с сохранёнными данными и ограничения. Добавьте запись в [CHANGELOG](CHANGELOG.md).

Структура: `NetworkProbe`/`MtProto` — сеть; `Timeline`/`HistoryStorage`/`History` — наблюдения и статистика; `OfflineBackoff` — паузы без сети; `MonitorService` — жизненный цикл и планирование; `EventNotifications` — уведомления событий; `Prefs` — настройки; `MainActivity` — экран.

`BackupCodec` — ограниченный аутентифицированный формат; `BackupStore` — атомарный журнал замены; `BackupUi` — document picker и парольные диалоги. Формат и правила переноса описаны в [docs/backup.md](docs/backup.md).

## Аудит зависимостей

```sh
bash ./gradlew :app:dependencyInventory
python3 scripts/test_dependency_audit.py
python3 scripts/dependency-audit.py app/build/reports/dependencies/resolved.json app/build/reports/dependencies/osv-report.json
```

Инвентарь включает транзитивные Maven-модули production runtime, unit и instrumentation classpaths. `runtime.cdx.json` — CycloneDX 1.5 только для production runtime. Проверяются точные разрешённые версии через OSV с учётом пагинации и отозванных записей. Пустой runtime, неразрешённые зависимости, неполный ответ, недоступность API и неотозванная уязвимость блокируют CI. Это проверка известных уязвимостей, а не доказательство их отсутствия; инструменты JDK/SDK и Gradle-плагины не входят в runtime SBOM.

Защита существующего signing backup и восстановление описаны в [docs/signing-backup.md](docs/signing-backup.md). Не удаляйте парольную копию до подтверждённого восстановления.

## Совместимость и выпуск

- Минимум Android 8.0/API 26. Не добавляйте зависимости или новую архитектуру без конкретной необходимости.
- Не превращайте UNKNOWN в утверждение об отсутствии интернета или блокировке. Наличие VPN не доказывает работоспособность туннеля.
- Не считайте пробелы истории временем сбоя; сохраняйте старые наблюдения при ошибках чтения и записи.
- Не коммитьте ключи подписи, пароли и локальные пути. Не создавайте новый production-ключ для обычного выпуска: это нарушит обновления.
- Для релиза измените `versionName`, добавьте `docs/releases/<versionName>.md`, объедините проверенный PR и запустите `Build APK` на `main` с `publish=true`. CI задаёт растущий `versionCode` и тег `v<versionName>.<run_number>`.

Код распространяется под [MIT](LICENSE). Вклад в репозиторий принимается на тех же условиях.
